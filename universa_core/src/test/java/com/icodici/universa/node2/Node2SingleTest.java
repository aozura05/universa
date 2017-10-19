/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>
 * Written by Maxim Pogorelov <pogorelovm23@gmail.com>, 10/19/17.
 *
 */

package com.icodici.universa.node2;

import com.icodici.universa.HashId;
import com.icodici.universa.contract.Contract;
import com.icodici.universa.node.*;
import com.icodici.universa.node2.network.Network;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class Node2SingleTest extends TestCase {

    private static final String ROOT_PATH = "./src/test_contracts/";

    Network network;
    NetConfig nc;
    Config config;
    Node node;
    NodeInfo myInfo;
    PostgresLedger ledger;

    @Before
    public void setUp() throws Exception {
        init(1, 1);
    }

    @Test
    public void registerGoodItem() throws Exception {
        TestItem ok = new TestItem(true);
        node.registerItem(ok);
        ItemResult r = node.waitItem(ok.getId(), 100);
        assertEquals(ItemState.APPROVED, r.state);
    }

    @Test
    public void registerBadItem() throws Exception {
        TestItem bad = new TestItem(false);
        node.registerItem(bad);
        ItemResult r = node.waitItem(bad.getId(), 100);
        assertEquals(ItemState.DECLINED, r.state);
    }

    @Test
    public void checkItem() throws Exception {
        TestItem ok = new TestItem(true);
        TestItem bad = new TestItem(false);
        node.registerItem(ok);
        node.registerItem(bad);
        node.waitItem(ok.getId(), 100);
        node.waitItem(bad.getId(), 100);
        assertEquals(ItemState.APPROVED, node.checkItem(ok.getId()).state);
        assertEquals(ItemState.DECLINED, node.checkItem(bad.getId()).state);
    }

    @Test
    public void shouldCreateItems() throws Exception {
        TestItem item = new TestItem(true);

        node.registerItem(item);
        ItemResult result = node.waitItem(item.getId(), 100);
        assertEquals(ItemState.APPROVED, result.state);

        result = node.waitItem(item.getId(), 100);
        assertEquals(ItemState.APPROVED, result.state);
        result = node.waitItem(item.getId(), 100);
        assertEquals(ItemState.APPROVED, result.state);

        result = node.checkItem(item.getId());
        assertEquals(ItemState.APPROVED, result.state);
    }

    @Test
    public void shouldDeclineItems() throws Exception {
        TestItem item = new TestItem(false);

        node.registerItem(item);
        ItemResult result = node.waitItem(item.getId(), 100);
        assertEquals(ItemState.DECLINED, result.state);

        result = node.waitItem(item.getId(), 100);
        assertEquals(ItemState.DECLINED, result.state);
        result = node.waitItem(item.getId(), 100);
        assertEquals(ItemState.DECLINED, result.state);

        result = node.checkItem(item.getId());
        assertEquals(ItemState.DECLINED, result.state);
    }

    @Test
    public void singleNodeMixApprovedAndDeclined() throws Exception {
        TestItem item = new TestItem(true);

        node.registerItem(item);
        ItemResult result = node.waitItem(item.getId(), 100);
        assertEquals(ItemState.APPROVED, result.state);

        result = node.waitItem(item.getId(), 100);
        assertEquals(ItemState.APPROVED, result.state);
        result = node.waitItem(item.getId(), 100);
        assertEquals(ItemState.APPROVED, result.state);

        result = node.checkItem(item.getId());
        assertEquals(ItemState.APPROVED, result.state);


        // Negative consensus
        TestItem item2 = new TestItem(false);

        node.registerItem(item2);
        ItemResult result2 = node.waitItem(item2.getId(), 100);
        assertEquals(ItemState.DECLINED, result2.state);

        result2 = node.waitItem(item2.getId(), 100);
        assertEquals(ItemState.DECLINED, result2.state);
        result2 = node.waitItem(item2.getId(), 100);
        assertEquals(ItemState.DECLINED, result2.state);

        result2 = node.checkItem(item2.getId());
        assertEquals(ItemState.DECLINED, result2.state);
    }

    @Test
    public void noQourumError() throws Exception {
        init(2, 2);

        TestItem item = new TestItem(true);

//        LogPrinter.showDebug(true);
        node.registerItem(item);
        try {
            node.waitItem(item.getId(), 100);
            fail("Expected exception to be thrown.");
        } catch (TimeoutException te) {
            assertNotNull(te);
        }

        @NonNull ItemResult checkedItem = node.checkItem(item.getId());

        assertEquals(ItemState.PENDING_POSITIVE, checkedItem.state);
        assertTrue(checkedItem.expiresAt.isBefore(ZonedDateTime.now().plusHours(5)));

        TestItem item2 = new TestItem(false);

        node.registerItem(item2);
        try {
            node.waitItem(item2.getId(), 100);
            fail("Expected exception to be thrown.");
        } catch (TimeoutException te) {
            assertNotNull(te);
        }

        checkedItem = node.checkItem(item2.getId());
        assertEquals(ItemState.PENDING_NEGATIVE, checkedItem.state);
    }

    @Test
    public void timeoutError() throws Exception {
        init(1, 1);
        config.setMaxElectionsTime(Duration.ofMillis(200));

        TestItem item = new TestItem(true);

        // We start elections but no node in the network know the source, so it
        // will short-circuit to self and then stop by the timeout:

        ItemResult itemResult = node.checkItem(item.getId());
        assertEquals(ItemState.UNDEFINED, itemResult.state);
        assertFalse(itemResult.haveCopy);
        assertNull(itemResult.createdAt);
        assertNull(itemResult.expiresAt);

        itemResult = node.waitItem(item.getId(), 100);
        assertEquals(ItemState.UNDEFINED, itemResult.state);

        itemResult = node.checkItem(item.getId());
        assertEquals(ItemState.UNDEFINED, itemResult.state);
    }

    @Test
    public void testNotCreatingOnReject() throws Exception {
        TestItem main = new TestItem(false);
        TestItem new1 = new TestItem(true);
        TestItem new2 = new TestItem(true);

        main.addNewItems(new1, new2);

        assertEquals(2, main.getNewItems().size());

        node.registerItem(main);

        ItemResult itemResult = node.waitItem(main.getId(), 100);

        assertEquals(ItemState.DECLINED, itemResult.state);

        @NonNull ItemResult itemNew1 = node.checkItem(new1.getId());
        assertEquals(ItemState.UNDEFINED, itemNew1.state);

        @NonNull ItemResult itemNew2 = node.checkItem(new2.getId());
        assertEquals(ItemState.UNDEFINED, itemNew2.state);
    }

    @Test
    public void rejectBadNewItem() throws Exception {
        TestItem main = new TestItem(true);
        TestItem new1 = new TestItem(true);
        TestItem new2 = new TestItem(false);

        main.addNewItems(new1, new2);

        assertEquals(2, main.getNewItems().size());

        node.registerItem(main);
        ItemResult itemResult = node.waitItem(main.getId(), 100);

        assertEquals(ItemState.DECLINED, itemResult.state);

        @NonNull ItemResult itemNew1 = node.checkItem(new1.getId());
        assertEquals(ItemState.UNDEFINED, itemNew1.state);

        @NonNull ItemResult itemNew2 = node.checkItem(new2.getId());
        assertEquals(ItemState.UNDEFINED, itemNew2.state);
    }

    @Test
    public void badNewDocumentsPreventAccepting() throws Exception {
        TestItem main = new TestItem(true);
        TestItem new1 = new TestItem(true);
        TestItem new2 = new TestItem(true);

        // and now we run the day for teh output document:
        node.registerItem(new2);

        main.addNewItems(new1, new2);

        assertEquals(2, main.getNewItems().size());

        @NonNull ItemResult item = node.checkItem(main.getId());
        assertEquals(ItemState.UNDEFINED, item.state);

        node.registerItem(main);

        ItemResult itemResult = node.checkItem(main.getId());
        assertEquals(ItemState.PENDING, itemResult.state);

        @NonNull ItemResult itemNew1 = node.checkItem(new1.getId());
        assertEquals(ItemState.UNDEFINED, itemNew1.state);

        // and this one was created before
        @NonNull ItemResult itemNew2 = node.checkItem(new2.getId());
        assertThat(itemNew2.state, anyOf(equalTo(ItemState.APPROVED), equalTo(ItemState.PENDING_POSITIVE)));
    }

    @Test
    public void acceptWithReferences() throws Exception {
        TestItem main = new TestItem(true);
        TestItem new1 = new TestItem(true);
        TestItem new2 = new TestItem(true);

        StateRecord existing1 = ledger.findOrCreate(HashId.createRandom());
        existing1.setState(ItemState.APPROVED).save();
        StateRecord existing2 = ledger.findOrCreate(HashId.createRandom());
        existing2.setState(ItemState.LOCKED).save();

        main.addReferencedItems(existing1.getId(), existing2.getId());
        main.addNewItems(new1, new2);

        main.addReferencedItems(existing1.getId(), existing2.getId());
        main.addNewItems(new1, new2);

        // check that main is fully approved
        node.registerItem(main);

        ItemResult itemResult = node.waitItem(main.getId(), 100);
        assertEquals(ItemState.APPROVED, itemResult.state);

        assertEquals(ItemState.APPROVED, node.checkItem(new1.getId()).state);
        assertEquals(ItemState.APPROVED, node.checkItem(new2.getId()).state);

        // and the references are intact
        assertEquals(ItemState.APPROVED, node.checkItem(existing1.getId()).state);
        assertEquals(ItemState.LOCKED, node.checkItem(existing2.getId()).state);
    }

    @Test
    public void badReferencesDeclineListStates() throws Exception {
        for (ItemState badState : Arrays.asList(
                ItemState.PENDING, ItemState.PENDING_POSITIVE, ItemState.PENDING_NEGATIVE, ItemState.UNDEFINED,
                ItemState.DECLINED, ItemState.REVOKED, ItemState.LOCKED_FOR_CREATION)
                ) {

            TestItem main = new TestItem(true);

            StateRecord existing1 = ledger.findOrCreate(HashId.createRandom());
            existing1.setState(ItemState.APPROVED).save();

            // but second is not good
            StateRecord existing2 = ledger.findOrCreate(HashId.createRandom());
            existing2.setState(badState).save();

            main.addReferencedItems(existing1.getId(), existing2.getId());

            // check that main is fully approved
            node.registerItem(main);
            ItemResult itemResult = node.waitItem(main.getId(), 100);
            assertEquals(ItemState.DECLINED, itemResult.state);

            // and the references are intact
            assertEquals(ItemState.APPROVED, existing1.reload().getState());
            assertEquals(badState, existing2.reload().getState());
        }
    }

    @Test
    public void badReferencesDecline() throws Exception {
        TestItem main = new TestItem(true);
        TestItem new1 = new TestItem(true);
        TestItem new2 = new TestItem(true);


        TestItem existing1 = new TestItem(false);
        TestItem existing2 = new TestItem(true);
        node.registerItem(existing1);
        node.registerItem(existing2);

        main.addReferencedItems(existing1.getId(), existing2.getId());
        main.addNewItems(new1, new2);

        // check that main is fully approved
        node.registerItem(main);

        ItemResult itemResult = node.waitItem(main.getId(), 100);
        assertEquals(ItemState.DECLINED, itemResult.state);

        assertEquals(ItemState.UNDEFINED, node.checkItem(new1.getId()).state);
        assertEquals(ItemState.UNDEFINED, node.checkItem(new2.getId()).state);

        // and the references are intact
        assertEquals(ItemState.DECLINED, node.checkItem(existing1.getId()).state);
        assertEquals(ItemState.APPROVED, node.checkItem(existing2.getId()).state);
    }

    @Test
    public void missingReferencesDecline() throws Exception {
        TestItem main = new TestItem(true);

        TestItem existing = new TestItem(true);
        node.registerItem(existing);
        @NonNull ItemResult existingItem = node.waitItem(existing.getId(), 100);

        // but second is missing
        HashId missingId = HashId.createRandom();

        main.addReferencedItems(existing.getId(), missingId);

        // check that main is fully approved
        node.registerItem(main);
        ItemResult itemResult = node.waitItem(main.getId(), 100);
        assertEquals(ItemState.DECLINED, itemResult.state);

        // and the references are intact
        assertEquals(ItemState.APPROVED, existingItem.state);

        assertNull(node.getItem(missingId));
    }

    @Test
    public void approveAndRevoke() throws Exception {
        TestItem main = new TestItem(true);

        StateRecord existing1 = ledger.findOrCreate(HashId.createRandom());
        existing1.setState(ItemState.APPROVED).save();
        StateRecord existing2 = ledger.findOrCreate(HashId.createRandom());
        existing2.setState(ItemState.APPROVED).save();

        main.addRevokingItems(new FakeItem(existing1), new FakeItem(existing2));

        // check that main is fully approved
        node.registerItem(main);
        ItemResult itemResult = node.waitItem(main.getId(), 100);
        assertEquals(ItemState.APPROVED, itemResult.state);

        // and the references are intact
        assertEquals(ItemState.REVOKED, node.checkItem(existing1.getId()).state);
        assertEquals(ItemState.REVOKED, node.checkItem(existing2.getId()).state);
    }

    @Test
    public void badRevokingItemsDeclineAndRemoveLock() throws Exception {
        for (ItemState badState : Arrays.asList(
                ItemState.PENDING, ItemState.PENDING_POSITIVE, ItemState.PENDING_NEGATIVE, ItemState.UNDEFINED,
                ItemState.DECLINED, ItemState.REVOKED, ItemState.LOCKED_FOR_CREATION)
                ) {

            TestItem main = new TestItem(true);

            StateRecord existing1 = ledger.findOrCreate(HashId.createRandom());
            existing1.setState(ItemState.APPROVED).save();
            // but second is not good
            StateRecord existing2 = ledger.findOrCreate(HashId.createRandom());
            existing2.setState(badState).save();

            main.addRevokingItems(new FakeItem(existing1), new FakeItem(existing2));

            node.registerItem(main);
            ItemResult itemResult = node.waitItem(main.getId(), 100);
            assertEquals(ItemState.DECLINED, itemResult.state);

            // and the references are intact
            assertEquals(ItemState.APPROVED, existing1.reload().getState());
            assertEquals(badState, existing2.reload().getState());

        }
    }

    //TODO max elections time is not implemented yet
    //@Test
    public void itemsCachedThenPurged() throws Exception {
        config.setMaxElectionsTime(Duration.ofMillis(50));

        TestItem main = new TestItem(true);
        node.registerItem(main);
        ItemResult itemResult = node.waitItem(main.getId(), 100);
        assertEquals(ItemState.APPROVED, itemResult.state);

        assertEquals(main, node.getItem(main.getId()));
        Thread.sleep(110);
        assertNull(node.getItem(main.getId()));
    }

    @Test
    public void createRealContract() throws Exception {
        Contract c = Contract.fromYamlFile(ROOT_PATH + "simple_root_contract.yml");
        c.addSignerKeyFromFile(ROOT_PATH +"_xer0yfe2nn1xthc.private.unikey");
        assertTrue(c.check());
        c.seal();

        node.registerItem(c);
        ItemResult itemResult = node.waitItem(c.getId(), 100);
        assertEquals(ItemState.APPROVED, itemResult.state);
    }


    private void init(int posCons, int negCons) throws IOException, SQLException {
        config = new Config();

        // The quorum bigger than the network capacity: we model the situation
        // when the system will not get the answer
        config.setPositiveConsensus(posCons);
        config.setNegativeConsensus(negCons);

        myInfo = new NodeInfo(getNodePublicKey(0), 1, "node1", "localhost",
                7101, 7102, 7104);
        nc = new NetConfig(asList(myInfo));
        network = new TestSingleNetwork(nc);

        Properties properties = new Properties();
        properties.setProperty("user", "postgres");
        properties.setProperty("password", "Abcd1234");

        ledger = new PostgresLedger(PostgresLedgerTest.CONNECTION_STRING, properties);
        node = new Node(config, myInfo, ledger, network);
    }


}