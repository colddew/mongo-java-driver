/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb;


class DBCursorAdapter implements MongoCursor {
    DBCursor cursor;

    public DBCursorAdapter(final DBCursor cursor) {
        this.cursor = cursor;
    }

    public long getCursorId() {
        return cursor.getCursorId();
    }

    public ServerAddress getServerAddress() {
        return cursor.getServerAddress();
    }

    public void close() {
        cursor.close();
    }

    public boolean hasNext() {
        return cursor.hasNext();
    }

    public DBObject next() {
        return cursor.next();
    }

    public void remove() {
        cursor.remove();
    }
}
