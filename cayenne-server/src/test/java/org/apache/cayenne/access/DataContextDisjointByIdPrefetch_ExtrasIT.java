/*****************************************************************
 *   Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 ****************************************************************/
package org.apache.cayenne.access;

import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.ValueHolder;
import org.apache.cayenne.configuration.server.ServerRuntime;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.query.PrefetchTreeNode;
import org.apache.cayenne.query.SelectQuery;
import org.apache.cayenne.test.jdbc.DBHelper;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.things.Bag;
import org.apache.cayenne.testdo.things.Ball;
import org.apache.cayenne.testdo.things.Box;
import org.apache.cayenne.testdo.things.Thing;
import org.apache.cayenne.unit.di.DataChannelInterceptor;
import org.apache.cayenne.unit.di.UnitTestClosure;
import org.apache.cayenne.unit.di.server.CayenneProjects;
import org.apache.cayenne.unit.di.server.ServerCase;
import org.apache.cayenne.unit.di.server.UseServerRuntime;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.apache.cayenne.exp.ExpressionFactory.matchExp;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@UseServerRuntime(CayenneProjects.THINGS_PROJECT)
public class DataContextDisjointByIdPrefetch_ExtrasIT extends ServerCase {

    @Inject
    protected DataContext context;

    @Inject
    private ServerRuntime runtime;

    @Inject
    protected DBHelper dbHelper;

    @Inject
    protected DataChannelInterceptor queryInterceptor;

    protected TableHelper tBag;
    protected TableHelper tBall;
    protected TableHelper tBox;
    protected TableHelper tBoxInfo;
    protected TableHelper tBoxThing;
    protected TableHelper tThing;

    @Before
    public void setUp() throws Exception {
        tBag = new TableHelper(dbHelper, "BAG");
        tBag.setColumns("ID", "NAME");

        tBall = new TableHelper(dbHelper, "BALL");
        tBall.setColumns("ID", "BOX_ID", "THING_VOLUME", "THING_WEIGHT");

        tBox = new TableHelper(dbHelper, "BOX");
        tBox.setColumns("ID", "BAG_ID", "NAME");

        tBoxInfo = new TableHelper(dbHelper, "BOX_INFO");
        tBoxInfo.setColumns("ID" ,"BOX_ID", "COLOR");

        tBoxThing = new TableHelper(dbHelper, "BOX_THING");
        tBoxThing.setColumns("BOX_ID", "THING_VOLUME", "THING_WEIGHT");

        tThing = new TableHelper(dbHelper, "THING");
        tThing.setColumns("ID", "VOLUME", "WEIGHT");
    }

    private void createBagWithTwoBoxesAndPlentyOfBallsDataSet() throws Exception {

        // because of SQLServer need to enable identity inserts per transaction,
        // inserting these objects via Cayenne, and then flushing the cache
        // http://technet.microsoft.com/en-us/library/ms188059.aspx

        tBag.insert(1, "b1");
        tBox.insert(1, 1, "big");
        tBoxInfo.insert(1, 1, "red");
        tBox.insert(2, 1, "small");
        tBoxInfo.insert(2, 2, "green");
        tThing.insert(1, 10, 10);
        tBall.insert(1, 1, 10, 10);
        tThing.insert(2, 20, 20);
        tBall.insert(2, 1, 20, 20);
        tThing.insert(3, 30, 30);
        tBall.insert(3, 2, 30, 30);
        tThing.insert(4, 40, 40);
        tBall.insert(4, 2, 40, 40);
        tThing.insert(5, 20, 10);
        tBall.insert(5, 2, 20, 10);
        tThing.insert(6, 40, 30);
        tBall.insert(6, 2, 40, 30);

        tBoxThing.insert(1, 10, 10);
        tBoxThing.insert(1, 20, 20);
        tBoxThing.insert(2, 30, 30);
        tBoxThing.insert(1, 40, 40);
        tBoxThing.insert(1, 20, 10);
        tBoxThing.insert(1, 40, 30);

    }

    @Test
    public void testFlattenedRelationship() throws Exception {
        createBagWithTwoBoxesAndPlentyOfBallsDataSet();

        SelectQuery query = new SelectQuery(Bag.class);
        query.addPrefetch(Bag.BALLS_PROPERTY).setSemantics(PrefetchTreeNode.DISJOINT_BY_ID_PREFETCH_SEMANTICS);
        final List<Bag> result = context.performQuery(query);

        queryInterceptor.runWithQueriesBlocked(new UnitTestClosure() {

            public void execute() {
                assertFalse(result.isEmpty());
                Bag b1 = result.get(0);
                List<Ball> balls = (List<Ball>) b1.readPropertyDirectly(Bag.BALLS_PROPERTY);
                assertNotNull(balls);
                assertFalse(((ValueHolder) balls).isFault());
                assertEquals(6, balls.size());

                List<Integer> volumes = new ArrayList<Integer>();
                for (Ball b : balls) {
                    assertEquals(PersistenceState.COMMITTED, b.getPersistenceState());
                    volumes.add(b.getThingVolume());
                }
                assertTrue(volumes.containsAll(Arrays.asList(10, 20, 30, 40, 20, 40)));
            }
        });
    }

    @Test
    public void testFlattenedMultiColumnRelationship() throws Exception {
        createBagWithTwoBoxesAndPlentyOfBallsDataSet();

        SelectQuery query = new SelectQuery(Box.class);
        query.addPrefetch(Box.THINGS_PROPERTY).setSemantics(PrefetchTreeNode.DISJOINT_BY_ID_PREFETCH_SEMANTICS);
        final List<Box> result = context.performQuery(query);

        queryInterceptor.runWithQueriesBlocked(new UnitTestClosure() {

            public void execute() {
                assertFalse(result.isEmpty());
                List<Integer> volumes = new ArrayList<Integer>();
                for (Box box : result) {
                    List<Thing> things = (List<Thing>) box.readPropertyDirectly(Box.THINGS_PROPERTY);
                    assertNotNull(things);
                    assertFalse(((ValueHolder) things).isFault());
                    for (Thing t : things) {
                        assertEquals(PersistenceState.COMMITTED, t.getPersistenceState());
                        volumes.add(t.getVolume());
                    }
                }
                assertEquals(6, volumes.size());
                assertTrue(volumes.containsAll(Arrays.asList(10, 20, 30, 40)));
            }
        });
    }

    @Test
    public void testLongFlattenedRelationship() throws Exception {
        createBagWithTwoBoxesAndPlentyOfBallsDataSet();

        SelectQuery query = new SelectQuery(Bag.class);
        query.addPrefetch(Bag.THINGS_PROPERTY).setSemantics(PrefetchTreeNode.DISJOINT_BY_ID_PREFETCH_SEMANTICS);
        final List<Bag> result = context.performQuery(query);

        queryInterceptor.runWithQueriesBlocked(new UnitTestClosure() {

            public void execute() {
                assertFalse(result.isEmpty());
                Bag b1 = result.get(0);
                List<Thing> things = (List<Thing>) b1.readPropertyDirectly(Bag.THINGS_PROPERTY);
                assertNotNull(things);
                assertFalse(((ValueHolder) things).isFault());
                assertEquals(6, things.size());

                List<Integer> volumes = new ArrayList<Integer>();
                for (Thing t : things) {
                    assertEquals(PersistenceState.COMMITTED, t.getPersistenceState());
                    volumes.add(t.getVolume());
                }
                assertTrue(volumes.containsAll(Arrays.asList(10, 20, 20, 30, 40, 40)));
            }
        });
    }

    @Test
    public void testMultiColumnRelationship() throws Exception {
        createBagWithTwoBoxesAndPlentyOfBallsDataSet();

        SelectQuery query = new SelectQuery(Ball.class);
        query.orQualifier(matchExp(Ball.THING_VOLUME_PROPERTY, 40).andExp(matchExp(Ball.THING_WEIGHT_PROPERTY, 30)));
        query.orQualifier(matchExp(Ball.THING_VOLUME_PROPERTY, 20).andExp(matchExp(Ball.THING_WEIGHT_PROPERTY, 10)));

        query.addPrefetch(Ball.THING_PROPERTY).setSemantics(PrefetchTreeNode.DISJOINT_BY_ID_PREFETCH_SEMANTICS);

        final List<Ball> balls = context.performQuery(query);

        queryInterceptor.runWithQueriesBlocked(new UnitTestClosure() {

            public void execute() {

                assertEquals(2, balls.size());

                balls.get(0).getThing().getVolume();
                balls.get(1).getThing().getVolume();
            }
        });
    }

    @Test
    public void testJointPrefetchInParent() throws Exception {
        createBagWithTwoBoxesAndPlentyOfBallsDataSet();

        SelectQuery query = new SelectQuery(Box.class);
        query.addPrefetch(Box.BALLS_PROPERTY).setSemantics(PrefetchTreeNode.JOINT_PREFETCH_SEMANTICS);
        query.addPrefetch(Box.BALLS_PROPERTY + "." + Ball.THING_PROPERTY).setSemantics(
                PrefetchTreeNode.DISJOINT_BY_ID_PREFETCH_SEMANTICS);
        final List<Box> result = context.performQuery(query);

        queryInterceptor.runWithQueriesBlocked(new UnitTestClosure() {

            public void execute() {
                assertFalse(result.isEmpty());
                List<Integer> volumes = new ArrayList<Integer>();
                for (Box box : result) {
                    List<Ball> balls = (List<Ball>) box.readPropertyDirectly(Box.BALLS_PROPERTY);
                    assertNotNull(balls);
                    assertFalse(((ValueHolder) balls).isFault());
                    for (Ball ball : balls) {
                        Thing thing = (Thing) ball.readPropertyDirectly(Ball.THING_PROPERTY);
                        assertNotNull(thing);
                        assertEquals(PersistenceState.COMMITTED, thing.getPersistenceState());
                        volumes.add(thing.getVolume());
                    }
                }
                assertEquals(6, volumes.size());
                assertTrue(volumes.containsAll(Arrays.asList(10, 20, 30, 40)));
            }
        });
    }

    @Test
    public void testJointPrefetchInChild() throws Exception {
        createBagWithTwoBoxesAndPlentyOfBallsDataSet();

        SelectQuery<Bag> query = new SelectQuery<Bag>(Bag.class);
        query.addPrefetch(Bag.BOXES.disjointById());
        query.addPrefetch(Bag.BOXES.dot(Box.BALLS).joint());
        final List<Bag> result = context.select(query);

        queryInterceptor.runWithQueriesBlocked(new UnitTestClosure() {

        	@Override
            public void execute() {
                assertFalse(result.isEmpty());

                Bag bag = result.get(0);
                List<Box> boxes = (List<Box>) bag.readPropertyDirectly(Bag.BOXES_PROPERTY);
                assertNotNull(boxes);
                assertFalse(((ValueHolder) boxes).isFault());
                assertEquals(2, boxes.size());

                Box big = null;
                List<String> names = new ArrayList<String>();
                for (Box box : boxes) {
                    assertEquals(PersistenceState.COMMITTED, box.getPersistenceState());
                    names.add(box.getName());
                    if (box.getName().equals("big")) {
                        big = box;
                    }
                }
                assertTrue(names.contains("big"));
                assertTrue(names.contains("small"));

                List<Ball> balls = (List<Ball>) big.readPropertyDirectly(Box.BALLS_PROPERTY);
                assertNotNull(balls);
                assertFalse(((ValueHolder) balls).isFault());
                assertEquals(2, balls.size());
                List<Integer> volumes = new ArrayList<Integer>();
                for (Ball ball : balls) {
                    assertEquals(PersistenceState.COMMITTED, ball.getPersistenceState());
                    volumes.add(ball.getThingVolume());
                }
                assertTrue(volumes.containsAll(Arrays.asList(10, 20)));
            }
        });
    }
}
