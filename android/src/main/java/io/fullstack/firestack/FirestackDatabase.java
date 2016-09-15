package io.fullstack.firestack;

import android.content.Context;
import android.util.Log;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import android.net.Uri;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReactContext;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;

import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.OnDisconnect;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;

class FirestackDBReference {
  private static final String TAG = "FirestackDBReference";

  private String mPath;
  private ReadableArray mModifiers;
  private Map mListeners;
  private FirestackDatabaseModule mDatabase;
  private ChildEventListener mEventListener;
  private ValueEventListener mValueListener;
  private ValueEventListener mOnceValueListener;
  private ReactContext mReactContext;

  public FirestackDBReference(final ReactContext context, final String path) {
    mReactContext = context;
    mPath = path;
  }

  public void setModifiers(final ReadableArray modifiers) {
    mModifiers = modifiers;
  }

  public void addChildEventListener(final String name, final ReadableArray modifiers) {
    final FirestackDBReference self = this;

    mEventListener = new ChildEventListener() {
      @Override
      public void onChildAdded(DataSnapshot dataSnapshot, String previousChildName) {
        self.handleDatabaseEvent(name, dataSnapshot);
      }

      @Override
      public void onChildChanged(DataSnapshot dataSnapshot, String previousChildName) {
        self.handleDatabaseEvent(name, dataSnapshot);
      }

      @Override
      public void onChildRemoved(DataSnapshot dataSnapshot) {
        self.handleDatabaseEvent(name, dataSnapshot);
      }

      @Override
      public void onChildMoved(DataSnapshot dataSnapshot, String previousChildName) {
        self.handleDatabaseEvent(name, dataSnapshot);
      }

      @Override
      public void onCancelled(DatabaseError error) {
        self.handleDatabaseError(name, error);
      }
    };
    Query ref = this.getDatabaseQueryAtPathAndModifiers(modifiers);
    ref.addChildEventListener(mEventListener);
  }

  public void addValueEventListener(final String name, final ReadableArray modifiers) {
    final FirestackDBReference self = this;

    mValueListener = new ValueEventListener() {
      @Override
      public void onDataChange(DataSnapshot dataSnapshot) {
        Log.d(TAG, "onDataChange called for " + name);
        self.handleDatabaseEvent("value", dataSnapshot);
      }

      @Override
      public void onCancelled(DatabaseError error) {
        Log.d(TAG, "onDataChange onCancelled called");
        self.handleDatabaseError("value", error);
      }
    };

    Query ref = this.getDatabaseQueryAtPathAndModifiers(modifiers);
    ref.addValueEventListener(mValueListener);
  }

  public void addOnceValueEventListener(final ReadableArray modifiers,
                                        final Callback callback) {
    final FirestackDBReference self = this;

    mOnceValueListener = new ValueEventListener() {
      @Override
      public void onDataChange(DataSnapshot dataSnapshot) {
        WritableMap data = FirestackUtils.dataSnapshotToMap("value", dataSnapshot);
        callback.invoke(null, data);
      }

      @Override
      public void onCancelled(DatabaseError error) {
        WritableMap err = Arguments.createMap();
        err.putInt("errorCode", error.getCode());
        err.putString("errorDetails", error.getDetails());
        err.putString("description", error.getMessage());
        callback.invoke(err);
      }
    };

    Query ref = this.getDatabaseQueryAtPathAndModifiers(modifiers);
    ref.addListenerForSingleValueEvent(mOnceValueListener);
  }

  public void cleanup() {
    this.removeChildEventListener();
    this.removeValueEventListener();
  }

  public void removeChildEventListener() {
    if (mEventListener != null) {
      DatabaseReference ref = this.getDatabaseRef();
      ref.removeEventListener(mEventListener);
      mEventListener = null;
    }
  }

  public void removeValueEventListener() {
    if (mValueListener != null) {
      DatabaseReference ref = this.getDatabaseRef();
      ref.removeEventListener(mValueListener);
      mValueListener = null;
    }
  }

  private void handleDatabaseEvent(final String name, final DataSnapshot dataSnapshot) {
    Log.d(TAG, "handleDatabaseEvent called: " + name);
    WritableMap data = FirestackUtils.dataSnapshotToMap(name, dataSnapshot);
    WritableMap evt  = Arguments.createMap();
    evt.putString("eventName", name);
    evt.putMap("body", data);
    
    FirestackUtils.sendEvent(mReactContext, "database_event", evt);
  }

  private void handleDatabaseError(final String name, final DatabaseError error) {
    Log.d(TAG, "handleDatabaseError called: " + name);
    WritableMap err = Arguments.createMap();
    err.putInt("errorCode", error.getCode());
    err.putString("errorDetails", error.getDetails());
    err.putString("description", error.getMessage());

    WritableMap evt  = Arguments.createMap();
    evt.putString("eventName", name);
    evt.putMap("body", err);

    FirestackUtils.sendEvent(mReactContext, "database_error", evt);
  }

  public DatabaseReference getDatabaseRef() {
    return FirebaseDatabase.getInstance().getReference(mPath);
  }

  private Query getDatabaseQueryAtPathAndModifiers(final ReadableArray modifiers) {
    DatabaseReference ref = this.getDatabaseRef();

    List<Object> strModifiers = FirestackUtils.recursivelyDeconstructReadableArray(modifiers);
    ListIterator<Object> it = strModifiers.listIterator();
    Query query = ref.orderByKey();

    while(it.hasNext()) {
      String str = (String) it.next();

      Log.d(TAG, "getReference with modifiers: " + str);
      String[] strArr = str.split(":");
      String methStr = strArr[0];

      if (methStr.equalsIgnoreCase("orderByKey")) {
        query = ref.orderByKey();
      } else if (methStr.equalsIgnoreCase("orderByValue")) {
        query = ref.orderByValue();
      } else if (methStr.equalsIgnoreCase("orderByPriority")) {
        query = ref.orderByPriority();
      } else if (methStr.contains("orderByChild")) {
        String key = strArr[1];
        Log.d(TAG, "orderByChild: " + key);
        query = ref.orderByChild(key);
      } else if (methStr.contains("limitToLast")) {
        String key = strArr[1];
        int limit = Integer.parseInt(key);
        Log.d(TAG, "limitToLast: " + limit);
        query = query.limitToLast(limit);
      } else if (methStr.contains("limitToFirst")) {
        String key = strArr[1];
        int limit = Integer.parseInt(key);
        Log.d(TAG, "limitToFirst: " + limit);
        query = query.limitToFirst(limit);
      } else if (methStr.contains("equalTo")) {
        String value = strArr[1];
        String key = strArr[2];
        if (key == null) {
          query = query.equalTo(value);
        } else {
          query = query.equalTo(value, key);
        }
      } else if (methStr.contains("endAt")) {
        String value = strArr[1];
        String key = strArr[2];
        if (key == null) {
          query = query.endAt(value);
        } else {
          query = query.endAt(value, key);
        }
      } else if (methStr.contains("startAt")) {
        String value = strArr[1];
        String key = strArr[2];
        if (key == null) {
          query = query.startAt(value);
        } else {
          query = query.startAt(value, key);
        }
      }
    }

    return query;
  }

}

class FirestackDatabaseModule extends ReactContextBaseJavaModule {

  private static final String TAG = "FirestackDatabase";

  private Context context;
  private ReactContext mReactContext;
  private HashMap<String, FirestackDBReference> mDBListeners = new HashMap<String, FirestackDBReference>();

  public FirestackDatabaseModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.context = reactContext;
    mReactContext = reactContext;
  }

  @Override
  public String getName() {
    return TAG;
  }

  // Persistence
  @ReactMethod
  public void enablePersistence(
    final Boolean enable,
    final Callback callback) {
      FirebaseDatabase.getInstance()
          .setPersistenceEnabled(enable);

      WritableMap res = Arguments.createMap();
      res.putString("status", "success");
      callback.invoke(null, res);
  }

  @ReactMethod
  public void keepSynced(
    final String path,
    final Boolean enable,
    final Callback callback) {
      DatabaseReference ref = this.getDatabaseReferenceAtPath(path);
      ref.keepSynced(enable);

      WritableMap res = Arguments.createMap();
      res.putString("status", "success");
      res.putString("path", path);
      callback.invoke(null, res);
  }

  // Database
  @ReactMethod
  public void set(
          final String path,
          final ReadableMap props,
          final Callback callback) {
    DatabaseReference ref = this.getDatabaseReferenceAtPath(path);

    final FirestackDatabaseModule self = this;
    Map<String, Object> m = FirestackUtils.recursivelyDeconstructReadableMap(props);

    DatabaseReference.CompletionListener listener = new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handleCallback("set", callback, error, ref);
      }
    };

    ref.setValue(m, listener);
  }

  @ReactMethod
  public void update(final String path,
                     final ReadableMap props,
                     final Callback callback) {
    DatabaseReference ref = this.getDatabaseReferenceAtPath(path);
    final FirestackDatabaseModule self = this;
    Map<String, Object> m = FirestackUtils.recursivelyDeconstructReadableMap(props);

    DatabaseReference.CompletionListener listener = new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handleCallback("update", callback, error, ref);
      }
    };

    ref.updateChildren(m, listener);
  }

  @ReactMethod
  public void remove(final String path,
                     final Callback callback) {
    DatabaseReference ref = this.getDatabaseReferenceAtPath(path);
    final FirestackDatabaseModule self = this;
    DatabaseReference.CompletionListener listener = new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError error, DatabaseReference ref) {
        handleCallback("remove", callback, error, ref);
      }
    };

    ref.removeValue(listener);
  }

  @ReactMethod
  public void push(final String path,
                   final ReadableMap props,
                   final Callback callback) {

    Log.d(TAG, "Called push with " + path);
    DatabaseReference ref = this.getDatabaseReferenceAtPath(path);
    DatabaseReference newRef = ref.push();

    final Uri url = Uri.parse(newRef.toString());
    final String newPath = url.getPath();

    ReadableMapKeySetIterator iterator = props.keySetIterator();
    if (iterator.hasNextKey()) {
      Log.d(TAG, "Passed value to push");
      // lame way to check if the `props` are empty
      final FirestackDatabaseModule self = this;
      Map<String, Object> m = FirestackUtils.recursivelyDeconstructReadableMap(props);

      DatabaseReference.CompletionListener listener = new DatabaseReference.CompletionListener() {
        @Override
        public void onComplete(DatabaseError error, DatabaseReference ref) {
          if (error != null) {
            WritableMap err = Arguments.createMap();
            err.putInt("errorCode", error.getCode());
            err.putString("errorDetails", error.getDetails());
            err.putString("description", error.getMessage());
            callback.invoke(err);
          } else {
            WritableMap res = Arguments.createMap();
            res.putString("status", "success");
            res.putString("ref", newPath);
            callback.invoke(null, res);
          }
        }
      };

      newRef.setValue(m, listener);
    } else {
      Log.d(TAG, "No value passed to push: " + newPath);
      WritableMap res = Arguments.createMap();
      res.putString("result", "success");
      res.putString("ref", newPath);
      callback.invoke(null, res);
    }
  }

  @ReactMethod
  public void on(final String path,
                 final ReadableArray modifiers,
                 final String name,
                 final Callback callback) {
    Log.d(TAG, "Setting a listener on event: " + name + " for path " + path);
    FirestackDBReference ref = this.getDBHandle(path);

    if (name.equals("value")) {
      ref.addValueEventListener(name, modifiers);
    } else {
      ref.addChildEventListener(name, modifiers);
    }

    this.saveDBHandle(path, ref);

    Log.d(TAG, "Added listener " + name);
    WritableMap resp = Arguments.createMap();
    resp.putString("handle", path);
    resp.putString("result", "success");
    callback.invoke(null, resp);
  }

  @ReactMethod
  public void onOnce(final String path,
                     final ReadableArray modifiers,
                     final String name,
                     final Callback callback) {
    Log.d(TAG, "Setting one-time listener on event: " + name + " for path " + path);
    FirestackDBReference ref = this.getDBHandle(path);
    ref.addOnceValueEventListener(modifiers, callback);
  }

  @ReactMethod
  public void off(final String path, final String name, final Callback callback) {
    // TODO
    FirestackDBReference ref = this.getDBHandle(path);

    if (name.equals("value")) {
      ref.removeValueEventListener();
    } else {
      ref.removeChildEventListener();
    }

    this.removeDBHandle(path);
    Log.d(TAG, "Removed listener " + name);
    WritableMap resp = Arguments.createMap();
    resp.putString("handle", path);
    resp.putString("result", "success");
    callback.invoke(null, resp);
  }

  // On Disconnect
  @ReactMethod
  public void onDisconnectSetObject(final String path, final ReadableMap props, final Callback callback) {
    DatabaseReference ref = this.getDatabaseReferenceAtPath(path);
    Map<String, Object> m = FirestackUtils.recursivelyDeconstructReadableMap(props);

    OnDisconnect od = ref.onDisconnect();
    od.setValue(m, new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
        handleCallback("onDisconnectSetObject", callback, databaseError, databaseReference);
      }
    });
  }

  @ReactMethod
  public void onDisconnectSetString(final String path, final String value, final Callback callback) {
    DatabaseReference ref = this.getDatabaseReferenceAtPath(path);

    OnDisconnect od = ref.onDisconnect();
    od.setValue(value, new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
        handleCallback("onDisconnectSetString", callback, databaseError, databaseReference);
      }
    });
  }

  @ReactMethod
  public void onDisconnectRemove(final String path, final Callback callback) {
    DatabaseReference ref = this.getDatabaseReferenceAtPath(path);

    OnDisconnect od = ref.onDisconnect();
    od.removeValue(new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
        handleCallback("onDisconnectRemove", callback, databaseError, databaseReference);
      }
    });
  }
  @ReactMethod
  public void onDisconnectCancel(final String path, final Callback callback) {
    DatabaseReference ref = this.getDatabaseReferenceAtPath(path);

    OnDisconnect od = ref.onDisconnect();
    od.cancel(new DatabaseReference.CompletionListener() {
      @Override
      public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
        handleCallback("onDisconnectCancel", callback, databaseError, databaseReference);
      }
    });
  }

  // Private helpers
  // private void handleDatabaseEvent(final String name, final DataSnapshot dataSnapshot) {
  //   WritableMap data = this.dataSnapshotToMap(name, dataSnapshot);
  //   WritableMap evt  = Arguments.createMap();
  //   evt.putString("eventName", name);
  //   evt.putMap("body", data);
  //   FirestackUtils.sendEvent(mReactContext, "database_event", evt);
  // }

  // private void handleDatabaseError(final String name, final DatabaseError error) {
  //   WritableMap err = Arguments.createMap();
  //   err.putInt("errorCode", error.getCode());
  //   err.putString("errorDetails", error.getDetails());
  //   err.putString("description", error.getMessage());

  //   WritableMap evt  = Arguments.createMap();
  //   evt.putString("eventName", name);
  //   evt.putMap("body", err);
  //   FirestackUtils.sendEvent(mReactContext, "database_error", evt);
  // }

  private void handleCallback(
          final String methodName,
          final Callback callback,
          final DatabaseError databaseError,
          final DatabaseReference databaseReference) {
    if (databaseError != null) {
      WritableMap err = Arguments.createMap();
      err.putInt("errorCode", databaseError.getCode());
      err.putString("errorDetails", databaseError.getDetails());
      err.putString("description", databaseError.getMessage());
      callback.invoke(err);
    } else {
      WritableMap res = Arguments.createMap();
      res.putString("status", "success");
      res.putString("method", methodName);
      callback.invoke(null, res);
    }
  }

  private FirestackDBReference getDBHandle(final String path) {
    if (!mDBListeners.containsKey(path)) {
      ReactContext ctx = getReactApplicationContext();
      mDBListeners.put(path, new FirestackDBReference(ctx, path));
    }

    return mDBListeners.get(path);
  }

  private void saveDBHandle(final String path,
                            final FirestackDBReference dbRef) {
    this.removeDBHandle(path);
    mDBListeners.put(path, dbRef);
  }

  private void removeDBHandle(final String path) {
    if (mDBListeners.containsKey(path)) {
      FirestackDBReference r = mDBListeners.get(path);
      r.cleanup();
    }
  }

  // TODO: move to FirestackDBReference?
  private DatabaseReference getDatabaseReferenceAtPath(final String path) {
    DatabaseReference mDatabase = FirebaseDatabase.getInstance().getReference(path);
    return mDatabase;
  }

  private Query getDatabaseQueryAtPathAndModifiers(
          final String path,
          final ReadableArray modifiers) {
    DatabaseReference ref = this.getDatabaseReferenceAtPath(path);

    List<Object> strModifiers = FirestackUtils.recursivelyDeconstructReadableArray(modifiers);
    ListIterator<Object> it = strModifiers.listIterator();
    Query query = ref.orderByKey();

    while(it.hasNext()) {
      String str = (String) it.next();

      Log.d(TAG, "getReference with modifiers: " + str);
      String[] strArr = str.split(":");
      String methStr = strArr[0];

      if (methStr.equalsIgnoreCase("orderByKey")) {
        query = ref.orderByKey();
      } else if (methStr.equalsIgnoreCase("orderByValue")) {
        query = ref.orderByValue();
      } else if (methStr.equalsIgnoreCase("orderByPriority")) {
        query = ref.orderByPriority();
      } else if (methStr.contains("orderByChild")) {
        String key = strArr[1];
        Log.d(TAG, "orderByChild: " + key);
        query = ref.orderByChild(key);
      } else if (methStr.contains("limitToLast")) {
        String key = strArr[1];
        int limit = Integer.parseInt(key);
        Log.d(TAG, "limitToLast: " + limit);
        query = query.limitToLast(limit);
      } else if (methStr.contains("limitToFirst")) {
        String key = strArr[1];
        int limit = Integer.parseInt(key);
        Log.d(TAG, "limitToFirst: " + limit);
        query = query.limitToFirst(limit);
      } else if (methStr.contains("equalTo")) {
        String value = strArr[1];
        String key = strArr[2];
        if (key == null) {
          query = query.equalTo(value);
        } else {
          query = query.equalTo(value, key);
        }
      } else if (methStr.contains("endAt")) {
        String value = strArr[1];
        String key = strArr[2];
        if (key == null) {
          query = query.endAt(value);
        } else {
          query = query.endAt(value, key);
        }
      } else if (methStr.contains("startAt")) {
        String value = strArr[1];
        String key = strArr[2];
        if (key == null) {
          query = query.startAt(value);
        } else {
          query = query.startAt(value, key);
        }
      }
    }

    return query;
  }

  private WritableMap dataSnapshotToMap(String name, DataSnapshot dataSnapshot) {
    return FirestackUtils.dataSnapshotToMap(name, dataSnapshot);
  }

  private <Any> Any castSnapshotValue(DataSnapshot snapshot) {
    if (snapshot.hasChildren()) {
      WritableMap data = Arguments.createMap();
      for (DataSnapshot child : snapshot.getChildren()) {
        Any castedChild = castSnapshotValue(child);
        switch (castedChild.getClass().getName()) {
          case "java.lang.Boolean":
            data.putBoolean(child.getKey(), (Boolean) castedChild);
            break;
          case "java.lang.Integer":
            data.putInt(child.getKey(), (Integer) castedChild);
            break;
          case "java.lang.Double":
            data.putDouble(child.getKey(), (Double) castedChild);
            break;
          case "java.lang.String":
            data.putString(child.getKey(), (String) castedChild);
            break;
          case "com.facebook.react.bridge.WritableNativeMap":
            data.putMap(child.getKey(), (WritableMap) castedChild);
            break;
        }
      }
      return (Any) data;
    } else {
      if (snapshot.getValue() != null) {
        String type = snapshot.getValue().getClass().getName();
        switch (type) {
          case "java.lang.Boolean":
            return (Any)((Boolean) snapshot.getValue());
          case "java.lang.Long":
            return (Any)((Integer)(((Long) snapshot.getValue()).intValue()));
          case "java.lang.Double":
            return (Any)((Double) snapshot.getValue());
          case "java.lang.String":
            return (Any)((String) snapshot.getValue());
          default:
            return (Any) null;
        }
      } else {
        return (Any) null;
      }
    }
  }
}
