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
package org.apache.cassandra.transport;

import java.util.concurrent.ConcurrentMap;

import io.netty.channel.Channel;
import org.apache.cassandra.auth.IAuthenticator;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.cliffc.high_scale_lib.NonBlockingHashMap;

public class ServerConnection extends Connection
{
    private enum State { UNINITIALIZED, AUTHENTICATION, READY }

    private volatile IAuthenticator.SaslNegotiator saslNegotiator;
    private final ClientState clientState;
    private volatile State state;

    //key是streamId
    private final ConcurrentMap<Integer, QueryState> queryStates = new NonBlockingHashMap<Integer, QueryState>();

    //在Frame.Decoder.decode调用
    public ServerConnection(Channel channel, int version, Connection.Tracker tracker)
    {
        super(channel, version, tracker);
        this.clientState = ClientState.forExternalCalls(channel.remoteAddress());
        this.state = State.UNINITIALIZED;
    }

    private QueryState getQueryState(int streamId)
    {
        QueryState qState = queryStates.get(streamId);
        if (qState == null)
        {
            // In theory we shouldn't get any race here, but it never hurts to be careful
            QueryState newState = new QueryState(clientState);
            if ((qState = queryStates.putIfAbsent(streamId, newState)) == null)
                qState = newState;
        }
        return qState;
    }

    //在Message.Dispatcher.messageReceived调用
    //type是请求消息类型
    public QueryState validateNewMessage(Message.Type type, int version, int streamId)
    {
        switch (state)
        {
            case UNINITIALIZED:
                //说明第一个消息必须是STARTUP或OPTIONS
                if (type != Message.Type.STARTUP && type != Message.Type.OPTIONS)
                    throw new ProtocolException(String.format("Unexpected message %s, expecting STARTUP or OPTIONS", type));
                break;
            case AUTHENTICATION:
                // Support both SASL auth from protocol v2 and the older style Credentials auth from v1
                if (type != Message.Type.AUTH_RESPONSE && type != Message.Type.CREDENTIALS)
                    throw new ProtocolException(String.format("Unexpected message %s, expecting %s", type, version == 1 ? "CREDENTIALS" : "SASL_RESPONSE"));
                break;
            case READY:
                if (type == Message.Type.STARTUP) //STARTUP这个请求消息只能发一次
                    throw new ProtocolException("Unexpected message STARTUP, the connection is already initialized");
                break;
            default:
                throw new AssertionError();
        }
        return getQueryState(streamId);
    }

    //在Message.Dispatcher.messageReceived调用
    public void applyStateTransition(Message.Type requestType, Message.Type responseType)
    {
        switch (state)
        {
            case UNINITIALIZED:
                if (requestType == Message.Type.STARTUP)
                {
                    if (responseType == Message.Type.AUTHENTICATE)
                        state = State.AUTHENTICATION;
                    else if (responseType == Message.Type.READY)
                        state = State.READY;
                }
                break;
            case AUTHENTICATION:
                // Support both SASL auth from protocol v2 and the older style Credentials auth from v1
                assert requestType == Message.Type.AUTH_RESPONSE || requestType == Message.Type.CREDENTIALS;

                if (responseType == Message.Type.READY || responseType == Message.Type.AUTH_SUCCESS)
                {
                    state = State.READY;
                    // we won't use the authenticator again, null it so that it can be GC'd
                    saslNegotiator = null;
                }
                break;
            case READY:
                break;
            default:
                throw new AssertionError();
        }
    }

    //在org.apache.cassandra.transport.messages.AuthResponse.execute(QueryState)调用
    public IAuthenticator.SaslNegotiator getSaslNegotiator()
    {
        if (saslNegotiator == null)
            saslNegotiator = DatabaseDescriptor.getAuthenticator().newSaslNegotiator();
        return saslNegotiator;
    }
}
