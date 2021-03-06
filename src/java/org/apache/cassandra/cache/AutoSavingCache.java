/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cache;

import java.io.*;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.cliffc.high_scale_lib.NonBlockingHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.ScheduledExecutors;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.db.compaction.CompactionInfo;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.io.FSWriteError;
import org.apache.cassandra.io.util.*;
import org.apache.cassandra.service.CacheService;
import org.apache.cassandra.utils.JVMStabilityInspector;
import org.apache.cassandra.utils.Pair;

//会把缓存内容定期保存到硬盘
public class AutoSavingCache<K extends CacheKey, V> extends InstrumentingCache<K, V>
{
    public interface IStreamFactory
    {
        public InputStream getInputStream(File path) throws FileNotFoundException;
        public OutputStream getOutputStream(File path) throws FileNotFoundException;
    }

    private static final Logger logger = LoggerFactory.getLogger(AutoSavingCache.class);

    /** True if a cache flush is currently executing: only one may execute at a time. */
    public static final Set<CacheService.CacheType> flushInProgress = new NonBlockingHashSet<CacheService.CacheType>();

    protected volatile ScheduledFuture<?> saveTask;
    protected final CacheService.CacheType cacheType;

    private final CacheSerializer<K, V> cacheLoader;
    private static final String CURRENT_VERSION = "b";

    private static volatile IStreamFactory streamFactory = new IStreamFactory()
    {
        public InputStream getInputStream(File path) throws FileNotFoundException
        {
            return new FileInputStream(path);
        }

        public OutputStream getOutputStream(File path) throws FileNotFoundException
        {
            return new FileOutputStream(path);
        }
    };

    // Unused, but exposed for a reason. See CASSANDRA-8096.
    public static void setStreamFactory(IStreamFactory streamFactory)
    {
        AutoSavingCache.streamFactory = streamFactory;
    }

    public AutoSavingCache(ICache<K, V> cache, CacheService.CacheType cacheType, CacheSerializer<K, V> cacheloader)
    {
        super(cacheType.toString(), cache);
        this.cacheType = cacheType;
        this.cacheLoader = cacheloader;
    }

    public File getCachePath(UUID cfId, String version)
    {
        Pair<String, String> names = Schema.instance.getCF(cfId);
        return DatabaseDescriptor.getSerializedCachePath(names.left, names.right, cfId, cacheType, version);
    }

    public Writer getWriter(int keysToSave)
    {
        return new Writer(keysToSave);
    }

    public void scheduleSaving(int savePeriodInSeconds, final int keysToSave)
    {
        if (saveTask != null)
        {
            saveTask.cancel(false); // Do not interrupt an in-progress save
            saveTask = null;
        }
        if (savePeriodInSeconds > 0)
        {
            Runnable runnable = new Runnable()
            {
                public void run()
                {
                    submitWrite(keysToSave);
                }
            };
            saveTask = ScheduledExecutors.optionalTasks.scheduleWithFixedDelay(runnable,
                                                                               savePeriodInSeconds,
                                                                               savePeriodInSeconds,
                                                                               TimeUnit.SECONDS);
        }
    }

    public int loadSaved(ColumnFamilyStore cfs)
    {
        int count = 0;
        long start = System.nanoTime();

        // modern format, allows both key and value (so key cache load can be purely sequential)
//<<<<<<< HEAD
//        //如my-test-data\saved_caches\mytest-keysindextest.KeysIndexTest_index_f1-KeyCache
//        File path = getCachePath(cfs.keyspace.getName(), cfs.name, cfs.metadata.cfId, CURRENT_VERSION);
//        // if path does not exist, try without cfId (assuming saved cache is created with current CF)
//        if (!path.exists())
//            path = getCachePath(cfs.keyspace.getName(), cfs.name, null, CURRENT_VERSION);
//=======
        File path = getCachePath(cfs.metadata.cfId, CURRENT_VERSION);
        if (path.exists())
        {
            DataInputStream in = null;
            try
            {
                logger.info(String.format("reading saved cache %s", path));
                in = new DataInputStream(new LengthAvailableInputStream(new BufferedInputStream(streamFactory.getInputStream(path)), path.length()));
                List<Future<Pair<K, V>>> futures = new ArrayList<Future<Pair<K, V>>>();
                while (in.available() > 0)
                {
                    Future<Pair<K, V>> entry = cacheLoader.deserialize(in, cfs);
                    // Key cache entry can return null, if the SSTable doesn't exist.
                    if (entry == null)
                        continue;
                    futures.add(entry);
                    count++;
                }

                for (Future<Pair<K, V>> future : futures)
                {
                    Pair<K, V> entry = future.get();
                    if (entry != null)
                        put(entry.left, entry.right);
                }
            }
            catch (Exception e)
            {
                JVMStabilityInspector.inspectThrowable(e);
                logger.debug(String.format("harmless error reading saved cache %s", path.getAbsolutePath()), e);
            }
            finally
            {
                FileUtils.closeQuietly(in);
            }
        }
        if (logger.isDebugEnabled())
            logger.debug("completed reading ({} ms; {} keys) saved cache {}",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), count, path);
        return count;
    }

    public Future<?> submitWrite(int keysToSave)
    {
        //会调用Writer.saveCache
        return CompactionManager.instance.submitCacheWrite(getWriter(keysToSave));
    }

    public class Writer extends CompactionInfo.Holder
    {
        private final Iterator<K> keyIterator;
        private final CompactionInfo info;
        private long keysWritten;
        private final long keysEstimate;

        protected Writer(int keysToSave)
        {
            int size = size();
            if (keysToSave >= size || keysToSave == 0)
            {
                keyIterator = keyIterator();
                keysEstimate = size;
            }
            else
            {
                keyIterator = hotKeyIterator(keysToSave);
                keysEstimate = keysToSave;
            }

            OperationType type;
            if (cacheType == CacheService.CacheType.KEY_CACHE)
                type = OperationType.KEY_CACHE_SAVE;
            else if (cacheType == CacheService.CacheType.ROW_CACHE)
                type = OperationType.ROW_CACHE_SAVE;
            else if (cacheType == CacheService.CacheType.COUNTER_CACHE)
                type = OperationType.COUNTER_CACHE_SAVE;
            else
                type = OperationType.UNKNOWN;

            info = new CompactionInfo(CFMetaData.denseCFMetaData(SystemKeyspace.NAME, cacheType.toString(), BytesType.instance),
                                      type,
                                      0,
                                      keysEstimate,
                                      "keys");
        }

        public CacheService.CacheType cacheType()
        {
            return cacheType;
        }

        public CompactionInfo getCompactionInfo()
        {
            // keyset can change in size, thus total can too
            // TODO need to check for this one... was: info.forProgress(keysWritten, Math.max(keysWritten, keys.size()));
            return info.forProgress(keysWritten, Math.max(keysWritten, keysEstimate));
        }

        public void saveCache()
        {
            logger.debug("Deleting old {} files.", cacheType);
            deleteOldCacheFiles();

            if (!keyIterator.hasNext())
            {
                logger.debug("Skipping {} save, cache is empty.", cacheType);
                return;
            }

            long start = System.nanoTime();

            HashMap<UUID, DataOutputPlus> writers = new HashMap<>();
            HashMap<UUID, OutputStream> streams = new HashMap<>();
            HashMap<UUID, File> paths = new HashMap<>();

            try
            {
                while (keyIterator.hasNext())
                {
                    K key = keyIterator.next();
                    UUID cfId = key.getCFId();
                    if (!Schema.instance.hasCF(key.getCFId()))
                        continue; // the table has been dropped.

                    DataOutputPlus writer = writers.get(cfId);
                    if (writer == null)
                    {
                        File writerPath = tempCacheFile(cfId);
                        OutputStream stream;
                        try
                        {
                            stream = streamFactory.getOutputStream(writerPath);
                            writer = new DataOutputStreamPlus(stream);
                        }
                        catch (FileNotFoundException e)
                        {
                            throw new RuntimeException(e);
                        }
                        paths.put(cfId, writerPath);
                        streams.put(cfId, stream);
                        writers.put(cfId, writer);
                    }

                    try
                    {
                        cacheLoader.serialize(key, writer);
                    }
                    catch (IOException e)
                    {
                        throw new FSWriteError(e, paths.get(cfId));
                    }

                    keysWritten++;
                    if (keysWritten >= keysEstimate)
                        break;
                }
            }
            finally
            {
                if (keyIterator instanceof Closeable)
                    try
                    {
                        ((Closeable)keyIterator).close();
                    }
                    catch (IOException ignored)
                    {
                        // not thrown (by OHC)
                    }

                for (OutputStream writer : streams.values())
                    FileUtils.closeQuietly(writer);
            }

            for (Map.Entry<UUID, DataOutputPlus> entry : writers.entrySet())
            {
                UUID cfId = entry.getKey();

                File tmpFile = paths.get(cfId);
                File cacheFile = getCachePath(cfId, CURRENT_VERSION);

                cacheFile.delete(); // ignore error if it didn't exist
                if (!tmpFile.renameTo(cacheFile))
                    logger.error("Unable to rename {} to {}", tmpFile, cacheFile);
            }

            logger.info("Saved {} ({} items) in {} ms", cacheType, keysWritten, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }

        private File tempCacheFile(UUID cfId)
        {
            File path = getCachePath(cfId, CURRENT_VERSION);
            return FileUtils.createTempFile(path.getName(), null, path.getParentFile());
        }

        private void deleteOldCacheFiles()
        {
            File savedCachesDir = new File(DatabaseDescriptor.getSavedCachesLocation());
            assert savedCachesDir.exists() && savedCachesDir.isDirectory();
            File[] files = savedCachesDir.listFiles();
            if (files != null)
            {
                String cacheNameFormat = String.format("%s-%s.db", cacheType.toString(), CURRENT_VERSION);
                for (File file : files)
                {
                    if (!file.isFile())
                        continue; // someone's been messing with our directory.  naughty!

                    if (file.getName().endsWith(cacheNameFormat) 
                     || file.getName().endsWith(cacheType.toString()))
                    {
                        if (!file.delete())
                            logger.warn("Failed to delete {}", file.getAbsolutePath());
                    }
                }
            }
            else
            {
                logger.warn("Could not list files in {}", savedCachesDir);
            }
        }
    }

    public interface CacheSerializer<K extends CacheKey, V>
    {
        void serialize(K key, DataOutputPlus out) throws IOException;

        Future<Pair<K, V>> deserialize(DataInputStream in, ColumnFamilyStore cfs) throws IOException;
    }
}
