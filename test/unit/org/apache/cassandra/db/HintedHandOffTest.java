/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */
package org.apache.cassandra.db;

import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com.google.common.collect.Iterators;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.db.marshal.UUIDType;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.Pair;

import static org.junit.Assert.assertEquals;
import static org.apache.cassandra.cql3.QueryProcessor.executeInternal;

public class HintedHandOffTest extends SchemaLoader
{

    public static final String KEYSPACE4 = "Keyspace4";
    public static final String STANDARD1_CF = "Standard1";
    public static final String STANDARD3_CF = "Standard3";
    public static final String COLUMN1 = "column1";

    // Test compaction of hints column family. It shouldn't remove all columns on compaction.
    @Test
    public void testCompactionOfHintsCF() throws Exception
    {
        // prepare hints column family
        Keyspace systemKeyspace = Keyspace.open("system");
        ColumnFamilyStore hintStore = systemKeyspace.getColumnFamilyStore(SystemKeyspace.HINTS_CF);
        hintStore.clearUnsafe();
        hintStore.metadata.gcGraceSeconds(36000); // 10 hours
        hintStore.setCompactionStrategyClass(SizeTieredCompactionStrategy.class.getCanonicalName());
        hintStore.disableAutoCompaction();

        // insert 1 hint
        Mutation rm = new Mutation(KEYSPACE4, ByteBufferUtil.bytes(1));
        rm.add(STANDARD1_CF, Util.cellname(COLUMN1), ByteBufferUtil.EMPTY_BYTE_BUFFER, System.currentTimeMillis());

        HintedHandOffManager.instance.hintFor(rm,
                                              System.currentTimeMillis(),
                                              HintedHandOffManager.calculateHintTTL(rm),
                                              Pair.create(InetAddress.getByName("127.0.0.1"), UUID.randomUUID()))
                                     .apply();

        // flush data to disk
        hintStore.forceBlockingFlush();
        assertEquals(1, hintStore.getSSTables().size());

        // submit compaction
        HintedHandOffManager.instance.compact();

        // single row should not be removed because of gc_grace_seconds
        // is 10 hours and there are no any tombstones in sstable
        assertEquals(1, hintStore.getSSTables().size());
    }

    @Test
    public void testHintTTL() throws Exception
    {
        // set hint_time_to_live_seconds on one of the column families
        Keyspace keyspace = Keyspace.open(KEYSPACE4);
        ColumnFamilyStore cf1 = keyspace.getColumnFamilyStore(STANDARD3_CF);
        cf1.metadata.hintTimeToLiveSeconds(7200); // 2 hours

        // prepare a mutation
        Mutation rm1 = new Mutation(KEYSPACE4, ByteBufferUtil.bytes(1));
        rm1.add(STANDARD1_CF, Util.cellname(COLUMN1), ByteBufferUtil.EMPTY_BYTE_BUFFER, System.currentTimeMillis());

        // the hint TTL should be calculated using gc_grace_seconds
        assertEquals(cf1.metadata.getGcGraceSeconds(), HintedHandOffManager.calculateHintTTL(rm1));

        // another mutation for for a CF where hint_time_to_live_seconds hasn't been set
        Mutation rm2 = new Mutation(KEYSPACE4, ByteBufferUtil.bytes(1));
        rm2.add(STANDARD3_CF, Util.cellname(COLUMN1), ByteBufferUtil.EMPTY_BYTE_BUFFER, System.currentTimeMillis());

        // the hint TTL calculation should use the explicitly set value
        assertEquals(7200, HintedHandOffManager.calculateHintTTL(rm2));

        // a mutation touching both CFs
        Mutation rm3 = new Mutation(KEYSPACE4, ByteBufferUtil.bytes(1));
        rm3.add(STANDARD1_CF, Util.cellname(COLUMN1), ByteBufferUtil.EMPTY_BYTE_BUFFER, System.currentTimeMillis());
        rm3.add(STANDARD3_CF, Util.cellname(COLUMN1), ByteBufferUtil.EMPTY_BYTE_BUFFER, System.currentTimeMillis());

        // the hint TTL is the minimum of gc_grace_seconds and hint_time_to_live_seconds
        assertEquals(7200, HintedHandOffManager.calculateHintTTL(rm3));
    }

    @Test
    public void testHintsMetrics() throws Exception
    {
        for (int i = 0; i < 99; i++)
            HintedHandOffManager.instance.metrics.incrPastWindow(InetAddress.getLocalHost());
        HintedHandOffManager.instance.metrics.log();

        UntypedResultSet rows = executeInternal("SELECT hints_dropped FROM system." + SystemKeyspace.PEER_EVENTS_CF);
        Map<UUID, Integer> returned = rows.one().getMap("hints_dropped", UUIDType.instance, Int32Type.instance);
        assertEquals(Iterators.getLast(returned.values().iterator()).intValue(), 99);
    }

    @Test(timeout = 5000)
    public void testTruncateHints() throws Exception
    {
        Keyspace systemKeyspace = Keyspace.open("system");
        ColumnFamilyStore hintStore = systemKeyspace.getColumnFamilyStore(SystemKeyspace.HINTS_CF);
        hintStore.clearUnsafe();

        // insert 1 hint
        Mutation rm = new Mutation(KEYSPACE4, ByteBufferUtil.bytes(1));
        rm.add(STANDARD1_CF, Util.cellname(COLUMN1), ByteBufferUtil.EMPTY_BYTE_BUFFER, System.currentTimeMillis());

        HintedHandOffManager.instance.hintFor(rm,
                                              System.currentTimeMillis(),
                                              HintedHandOffManager.calculateHintTTL(rm),
                                              Pair.create(InetAddress.getByName("127.0.0.1"), UUID.randomUUID()))
                                     .apply();

        assert getNoOfHints() == 1;

        HintedHandOffManager.instance.truncateAllHints();

        while(getNoOfHints() > 0)
        {
            Thread.sleep(100);
        }

        assert getNoOfHints() == 0;
    }

    private int getNoOfHints()
    {
        String req = "SELECT * FROM system.%s";
        UntypedResultSet resultSet = executeInternal(String.format(req, SystemKeyspace.HINTS_CF));
        return resultSet.size();
    }
}
