/*
 * The MIT License
 *
 * Copyright (c) 2013-2016 reark project contributors
 *
 * https://github.com/reark/reark/graphs/contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.reark.reark.data.store;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Pair;

import java.util.ArrayList;
import java.util.List;

import io.reark.reark.utils.Log;
import io.reark.reark.utils.Preconditions;
import rx.Observable;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;

/**
 * ContentProviderStore implements an Observable based item store that uses a content provider as
 * its data backing store.
 *
 * All content provider operations are threaded. The store executes put operations in order, but
 * provides no guarantee for the execution order between get and put operations.
 *
 * This in an abstract class that implements the content provider access and expects extending
 * classes to implement data type specific methods.
 *
 * @param <T> Type of the data this store contains.
 */
public abstract class ContentProviderStore<T> {
    private static final String TAG = ContentProviderStore.class.getSimpleName();

    @NonNull
    final protected ContentResolver contentResolver;

    @NonNull
    private final ContentObserver contentObserver = getContentObserver();

    @NonNull
    protected final PublishSubject<Pair<T, Uri>> updateSubject = PublishSubject.create();

    protected ContentProviderStore(@NonNull ContentResolver contentResolver) {
        Preconditions.checkNotNull(contentResolver, "Content Resolver cannot be null.");

        this.contentResolver = contentResolver;
        this.contentResolver.registerContentObserver(getContentUri(), true, contentObserver);

        updateSubject
                .onBackpressureBuffer()
                .observeOn(Schedulers.computation())
                .subscribe(pair -> {
                    updateIfValueChanged(this, pair);
                });
    }

    private static <T> void updateIfValueChanged(ContentProviderStore store, Pair<T, Uri> pair) {
        final Cursor cursor = store.contentResolver.query(pair.second, store.getProjection(), null, null, null);
        ContentValues newValues = store.getContentValuesForItem(pair.first);
        boolean valuesEqual = false;

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                ContentValues currentValues = store.readRaw(cursor);
                valuesEqual = store.contentValuesEqual(currentValues, newValues);

                if (!valuesEqual) {
                    Log.v(TAG, "Merging values at " + pair.second);
                    newValues = store.mergeValues(currentValues, newValues);
                    valuesEqual = store.contentValuesEqual(currentValues, newValues);
                }
            }
            cursor.close();
        }

        if (valuesEqual) {
            Log.v(TAG, "Data already up to date at " + pair.second);
        } else if (store.contentResolver.update(pair.second, newValues, null, null) == 0) {
            final Uri resultUri = store.contentResolver.insert(pair.second, newValues);
            Log.v(TAG, "Inserted at " + resultUri);
        } else {
            Log.v(TAG, "Updated at " + pair.second);
        }
    }

    @NonNull
    protected static Handler createHandler(String name) {
        HandlerThread handlerThread = new HandlerThread(name);
        handlerThread.start();
        return new Handler(handlerThread.getLooper());
    }

    protected void put(T item, Uri uri) {
        Preconditions.checkNotNull(item, "Item to be inserted cannot be null");

        updateSubject.onNext(new Pair<>(item, uri));
    }

    @NonNull
    protected Observable<List<T>> get(Uri uri) {
        return Observable.just(uri)
                .observeOn(Schedulers.computation())
                .map(this::queryList);
    }

    @NonNull
    protected Observable<T> getOne(Uri uri) {
        return get(uri)
                .map(queryResults -> {
                    if (queryResults.size() == 0) {
                        return null;
                    } else if (queryResults.size() > 1) {
                        Log.w(TAG, "Multiple items found in a get for a single item:" + queryResults.size());
                    }

                    return queryResults.get(0);
                });
    }

    @NonNull
    private List<T> queryList(Uri uri) {
        Preconditions.checkNotNull(uri, "Uri cannot be null.");

        Cursor cursor = contentResolver.query(uri, getProjection(), null, null, null);
        List<T> list = new ArrayList<>();

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                list.add(read(cursor));
            }
            while (cursor.moveToNext()) {
                list.add(read(cursor));
            }
            cursor.close();
        }
        if (list.size() == 0) {
            Log.v(TAG, "Could not find with uri: " + uri);
        }
        return list;
    }

    @NonNull
    protected abstract Uri getContentUri();

    @NonNull
    protected abstract ContentObserver getContentObserver();

    @NonNull
    protected abstract String[] getProjection();

    @NonNull
    protected abstract T read(Cursor cursor);

    @NonNull
    protected abstract ContentValues readRaw(Cursor cursor);

    @NonNull
    protected abstract ContentValues getContentValuesForItem(T item);

    protected abstract boolean contentValuesEqual(@NonNull ContentValues v1, @NonNull ContentValues v2);

    @NonNull
    protected ContentValues mergeValues(@NonNull ContentValues v1, @NonNull ContentValues v2) {
        return v2; // Default behavior is new values overriding
    }
}
