/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.providers.contacts2;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Aggregates;
import android.provider.ContactsContract.AggregationExceptions;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.test.ProviderTestCase2;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.LargeTest;

/**
 * Unit tests for {@link ContactsProvider2} and {@link ContactAggregator}.
 *
 * Run the test like this:
 * <code>
 * adb shell am instrument -w \
 *         com.android.providers.contacts2.tests/android.test.InstrumentationTestRunner
 * </code>
 */
@LargeTest
public class ContactsProvider2Test
        extends ProviderTestCase2<ContactsProvider2Test.SynchrounousContactsProvider> {

    private static final String PACKAGE = "ContactsProvider2Test";

    private MockContentResolver mResolver;

    // Indicator allowing us to wipe data only once for the entire test suite
    private static Boolean sDataWiped = false;

    private static final String[] AGGREGATION_EXCEPTION_PROJECTION = new String[] {
            AggregationExceptions.TYPE,
            AggregationExceptions.AGGREGATE_ID,
            AggregationExceptions.CONTACT_ID
    };

    /**
     * A version of the {@link ContactsProvider2Test} class that performs aggregation
     * synchronously and wipes all data at construction time.
     */
    public static class SynchrounousContactsProvider extends ContactsProvider2 {

        private OpenHelper mOpenHelper;

        public SynchrounousContactsProvider() {
            super(false);
        }

        @Override
        protected OpenHelper getOpenHelper(final Context context) {
            if (mOpenHelper == null) {
                mOpenHelper = new OpenHelper(context);
            }
            return mOpenHelper;
        }

        @Override
        public boolean onCreate() {
            boolean created = super.onCreate();
            synchronized (sDataWiped) {
                if (!sDataWiped) {
                    sDataWiped = true;
                    wipeData();
                }
            }
            return created;
        }
    }

    public ContactsProvider2Test() {
        super(SynchrounousContactsProvider.class, ContactsContract.AUTHORITY);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mResolver = getMockContentResolver();
    }

    public void testCrudAggregationExceptions() throws Exception {
        long contactId1 = createContact();
        long aggregateId = queryAggregateId(contactId1);
        long contactId2 = createContact();

        setAggregationException(AggregationExceptions.TYPE_KEEP_IN, aggregateId, contactId2);

        // Refetch the row we have just inserted
        Cursor c = mResolver.query(AggregationExceptions.CONTENT_URI,
                AGGREGATION_EXCEPTION_PROJECTION, AggregationExceptions.AGGREGATE_ID + "="
                        + aggregateId, null, null);

        assertTrue(c.moveToFirst());
        assertEquals(AggregationExceptions.TYPE_KEEP_IN, c.getInt(0));
        assertEquals(aggregateId, c.getLong(1));
        assertEquals(contactId2, c.getLong(2));
        assertFalse(c.moveToNext());
        c.close();

        // Change from TYPE_KEEP_IN to TYPE_KEEP_OUT
        setAggregationException(AggregationExceptions.TYPE_KEEP_OUT, aggregateId, contactId2);

        c = mResolver.query(AggregationExceptions.CONTENT_URI,
                AGGREGATION_EXCEPTION_PROJECTION, AggregationExceptions.AGGREGATE_ID + "="
                        + aggregateId, null, null);

        assertTrue(c.moveToFirst());
        assertEquals(AggregationExceptions.TYPE_KEEP_OUT, c.getInt(0));
        assertEquals(aggregateId, c.getLong(1));
        assertEquals(contactId2, c.getLong(2));
        assertFalse(c.moveToNext());
        c.close();

        // Delete the rule
        setAggregationException(AggregationExceptions.TYPE_AUTOMATIC, aggregateId, contactId2);

        // Verify that the row is gone
        c = mResolver.query(AggregationExceptions.CONTENT_URI,
                AGGREGATION_EXCEPTION_PROJECTION, AggregationExceptions.AGGREGATE_ID + "="
                        + aggregateId, null, null);
        assertFalse(c.moveToFirst());
        c.close();
    }

    public void testAggregationCreatesNewAggregate() {
        long contactId = createContact();

        Uri resultUri = insertStructuredName(contactId, "Johna", "Smitha");

        // Parse the URI and confirm that it contains an ID
        assertTrue(ContentUris.parseId(resultUri) != 0);

        long aggregateId = queryAggregateId(contactId);
        assertTrue(aggregateId != 0);

        String displayName = queryDisplayName(aggregateId);
        assertEquals("Johna Smitha", displayName);
    }

    public void testAggregationOfExactFullNameMatch() {
        long contactId1 = createContact();
        insertStructuredName(contactId1, "Johnb", "Smithb");

        long contactId2 = createContact();
        insertStructuredName(contactId2, "Johnb", "Smithb");

        assertAggregated(contactId1, contactId2, "Johnb Smithb");
    }

    public void testAggregationOfCaseInsensitiveFullNameMatch() {
        long contactId1 = createContact();
        insertStructuredName(contactId1, "Johnc", "Smithc");

        long contactId2 = createContact();
        insertStructuredName(contactId2, "Johnc", "smithc");

        assertAggregated(contactId1, contactId2, "Johnc Smithc");
    }

    public void testAggregationOfLastNameMatch() {
        long contactId1 = createContact();
        insertStructuredName(contactId1, null, "Johnd");

        long contactId2 = createContact();
        insertStructuredName(contactId2, null, "johnd");

        assertAggregated(contactId1, contactId2, "Johnd");
    }

    public void testNonAggregationOfFirstNameMatch() {
        long contactId1 = createContact();
        insertStructuredName(contactId1, "Johne", "Smithe");

        long contactId2 = createContact();
        insertStructuredName(contactId2, "Johne", null);

        assertNotAggregated(contactId1, contactId2);
    }

    // TODO: should this be allowed to match?
    public void testNonAggregationOfLastNameMatch() {
        long contactId1 = createContact();
        insertStructuredName(contactId1, "Johnf", "Smithf");

        long contactId2 = createContact();
        insertStructuredName(contactId2, null, "Smithf");

        assertNotAggregated(contactId1, contactId2);
    }

    public void testAggregationOfConcatenatedFullNameMatch() {
        long contactId1 = createContact();
        insertStructuredName(contactId1, "Johng", "Smithg");

        long contactId2 = createContact();
        insertStructuredName(contactId2, "johngsmithg", null);

        assertAggregated(contactId1, contactId2, "Johng Smithg");
    }

    public void testAggregationOfNormalizedFullNameMatch() {
        long contactId1 = createContact();
        insertStructuredName(contactId1, "H\u00e9l\u00e8ne", "Bj\u00f8rn");

        long contactId2 = createContact();
        insertStructuredName(contactId2, "helene bjorn", null);

        assertAggregated(contactId1, contactId2, "H\u00e9l\u00e8ne Bj\u00f8rn");
    }

    public void testAggregationExceptionNeverMatch() {
        long contactId1 = createContact();
        insertStructuredName(contactId1, "Johnh", "Smithh");

        long contactId2 = createContact();
        insertStructuredName(contactId2, "Johnh", "Smithh");

        setAggregationException(AggregationExceptions.TYPE_KEEP_OUT,
                queryAggregateId(contactId1), contactId2);

        assertNotAggregated(contactId1, contactId2);
    }

    public void testAggregationExceptionAlwaysMatch() {
        long contactId1 = createContact();
        insertStructuredName(contactId1, "Johnj", "Smithj");

        long contactId2 = createContact();
        insertStructuredName(contactId2, "Johnjx", "Smithjx");

        long aggregateId1 = queryAggregateId(contactId1);
        long aggregateId2 = queryAggregateId(contactId2);

        setAggregationException(AggregationExceptions.TYPE_KEEP_IN,
                queryAggregateId(contactId1), contactId2);

        assertAggregated(contactId1, contactId2, "Johnjx Smithjx");

        // Assert that the empty aggregate got removed
        long newAggregateId1 = queryAggregateId(contactId1);
        if (aggregateId1 != newAggregateId1) {
            Cursor cursor = queryAggregate(aggregateId1);
            assertFalse(cursor.moveToFirst());
            cursor.close();
        } else {
            Cursor cursor = queryAggregate(aggregateId2);
            assertFalse(cursor.moveToFirst());
            cursor.close();
        }
    }

    public void testAggregationSuggestions() {
        long contactId1 = createContact();
        insertStructuredName(contactId1, "Johnk", "Smithk");

        long contactId2 = createContact();
        insertStructuredName(contactId2, "Johnk", "Smithk");

        setAggregationException(AggregationExceptions.TYPE_KEEP_OUT,
                queryAggregateId(contactId1), contactId2);

        long aggregateId1 = queryAggregateId(contactId1);
        long aggregateId2 = queryAggregateId(contactId2);

        final Uri aggregateUri = ContentUris.withAppendedId(Aggregates.CONTENT_URI, aggregateId1);
        Uri uri = Uri.withAppendedPath(aggregateUri,
                Aggregates.AggregationSuggestions.CONTENT_DIRECTORY);
        final Cursor cursor = mResolver.query(uri, new String[] { Aggregates._ID },
                null, null, null);

        assertTrue(cursor.moveToNext());
        long suggestedAggregateId = cursor.getLong(0);
        assertEquals(aggregateId2, suggestedAggregateId);
        cursor.close();
    }

    private long createContact() {
        ContentValues values = new ContentValues();
        Uri contactUri = mResolver.insert(Contacts.CONTENT_URI, values);
        return ContentUris.parseId(contactUri);
    }

    private Uri insertStructuredName(long contactId, String givenName, String familyName) {
        ContentValues values = new ContentValues();
        values.put(Data.CONTACT_ID, contactId);
        values.put(Data.PACKAGE, PACKAGE);
        values.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
        StringBuilder sb = new StringBuilder();
        if (givenName != null) {
            sb.append(givenName);
        }
        if (givenName != null && familyName != null) {
            sb.append(" ");
        }
        if (familyName != null) {
            sb.append(familyName);
        }
        values.put(StructuredName.DISPLAY_NAME, sb.toString());
        values.put(StructuredName.GIVEN_NAME, givenName);
        values.put(StructuredName.FAMILY_NAME, familyName);

        Uri resultUri = mResolver.insert(Data.CONTENT_URI, values);
        return resultUri;
    }

    private void setAggregationException(int type, long aggregateId, long contactId) {
        ContentValues values = new ContentValues();
        values.put(AggregationExceptions.AGGREGATE_ID, aggregateId);
        values.put(AggregationExceptions.CONTACT_ID, contactId);
        values.put(AggregationExceptions.TYPE, type);
        mResolver.update(AggregationExceptions.CONTENT_URI, values, null, null);
    }

    private Cursor queryContact(long contactId) {
        return mResolver.query(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), null,
                null, null, null);
    }

    private Cursor queryAggregate(long aggregateId) {
        return mResolver.query(ContentUris.withAppendedId(Aggregates.CONTENT_URI, aggregateId),
                null, null, null, null);
    }

    private long queryAggregateId(long contactId) {
        Cursor c = queryContact(contactId);
        assertTrue(c.moveToFirst());
        long aggregateId = c.getLong(c.getColumnIndex(Contacts.AGGREGATE_ID));
        c.close();
        return aggregateId;
    }

    private String queryDisplayName(long aggregateId) {
        Cursor c = queryAggregate(aggregateId);
        assertTrue(c.moveToFirst());
        String displayName = c.getString(c.getColumnIndex(Aggregates.DISPLAY_NAME));
        c.close();
        return displayName;
    }

    private void assertAggregated(long contactId1, long contactId2, String expectedDisplayName) {
        long aggregateId1 = queryAggregateId(contactId1);
        long aggregateId2 = queryAggregateId(contactId2);
        assertTrue(aggregateId1 == aggregateId2);

        String displayName = queryDisplayName(aggregateId1);
        assertEquals(expectedDisplayName, displayName);
    }

    private void assertNotAggregated(long contactId1, long contactId2) {
        long aggregateId1 = queryAggregateId(contactId1);
        long aggregateId2 = queryAggregateId(contactId2);
        assertTrue(aggregateId1 != aggregateId2);
    }
}
