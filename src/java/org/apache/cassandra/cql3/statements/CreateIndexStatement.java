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
package org.apache.cassandra.cql3.statements;

import java.util.Collections;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.config.IndexType;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.cql3.*;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.MigrationManager;
import org.apache.cassandra.thrift.ThriftValidation;
import org.apache.cassandra.transport.messages.ResultMessage;

/** A <code>CREATE INDEX</code> statement parsed from a CQL query. */
public class CreateIndexStatement extends SchemaAlteringStatement
{
    private static final Logger logger = LoggerFactory.getLogger(CreateIndexStatement.class);

    private final String indexName;
    private final ColumnIdentifier columnName;
    private final IndexPropDefs properties;
    private final boolean ifNotExists;

    public CreateIndexStatement(CFName name,
                                String indexName,
                                ColumnIdentifier columnName,
                                IndexPropDefs properties,
                                boolean ifNotExists)
    {
        super(name);
        this.indexName = indexName;
        this.columnName = columnName;
        this.properties = properties;
        this.ifNotExists = ifNotExists;
    }

    public void checkAccess(ClientState state) throws UnauthorizedException, InvalidRequestException
    {
        state.hasColumnFamilyAccess(keyspace(), columnFamily(), Permission.ALTER);
    }

    //参考测试: my.test.cql3.statements.IndexTest.test_CreateIndexStatement_validate()
    public void validate(ClientState state) throws RequestValidationException
    {
        CFMetaData cfm = ThriftValidation.validateColumnFamily(keyspace(), columnFamily());
        if (cfm.getDefaultValidator().isCommutative())
            throw new InvalidRequestException("Secondary indexes are not supported on counter tables");

        ColumnDefinition cd = cfm.getColumnDefinition(columnName);

        if (cd == null)
            throw new InvalidRequestException("No column definition found for column " + columnName);

        if (cd.getIndexType() != null)
        {
            if (ifNotExists)
                return;
            else //在同一个字段上不能建立两个索引，哪怕索引名不同也不行
                throw new InvalidRequestException("Index already exists");
        }

        properties.validate();

        // TODO: we could lift that limitation
        if (cfm.comparator.isDense() && cd.kind != ColumnDefinition.Kind.REGULAR)
            throw new InvalidRequestException(String.format("Secondary index on %s column %s is not yet supported for compact table", cd.kind, columnName));

        if (cd.kind == ColumnDefinition.Kind.PARTITION_KEY && cd.isOnAllComponents())
            throw new InvalidRequestException(String.format("Cannot add secondary index to already primarily indexed column %s", columnName));
    }

    //参考测试: my.test.cql3.statements.IndexTest.test_CreateIndexStatement_announceMigration()
    public void announceMigration() throws RequestValidationException
    {
        logger.debug("Updating column {} definition for index {}", columnName, indexName);
        CFMetaData cfm = Schema.instance.getCFMetaData(keyspace(), columnFamily()).clone();
        ColumnDefinition cd = cfm.getColumnDefinition(columnName);

        if (cd.getIndexType() != null && ifNotExists)
            return;

        if (properties.isCustom)
        {
            cd.setIndexType(IndexType.CUSTOM, properties.getOptions());
        }
        else if (cfm.comparator.isCompound())
        {
            Map<String, String> options = Collections.emptyMap();
            // For now, we only allow indexing values for collections, but we could later allow
            // to also index map keys, so we record that this is the values we index to make our
            // lives easier then.
            if (cd.type.isCollection())
                options = ImmutableMap.of("index_values", "");
            cd.setIndexType(IndexType.COMPOSITES, options);
        }
        else
        {
            cd.setIndexType(IndexType.KEYS, Collections.<String, String>emptyMap());
        }

        cd.setIndexName(indexName);
        cfm.addDefaultIndexNames();
        MigrationManager.announceColumnFamilyUpdate(cfm, false);
    }

    public ResultMessage.SchemaChange.Change changeType()
    {
        // Creating an index is akin to updating the CF
        return ResultMessage.SchemaChange.Change.UPDATED;
    }
}
