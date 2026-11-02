package android.util;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Backport of android.util.ArrayMap (API 19) for API 17.
 * Same public API; storage is a pair of parallel arrays with linear (hash-assisted) lookup,
 * which is what ArrayMap is meant for (small maps). Iteration order is insertion order.
 */
public final class ArrayMap<K, V> implements Map<K, V> {
    private int[] mHashes;
    private Object[] mKeys;
    private Object[] mValues;
    private int mSize;

    public ArrayMap() {
        this(0);
    }

    public ArrayMap(int capacity) {
        int c = Math.max(capacity, 4);
        mHashes = new int[c];
        mKeys = new Object[c];
        mValues = new Object[c];
    }

    public ArrayMap(ArrayMap<K, V> map) {
        this(map == null ? 0 : map.mSize);
        if (map != null) putAll(map);
    }

    private static int hash(Object k) {
        return k == null ? 0 : k.hashCode();
    }

    public void clear() {
        for (int i = 0; i < mSize; i++) {
            mKeys[i] = null;
            mValues[i] = null;
        }
        mSize = 0;
    }

    public void erase() {
        clear();
    }

    public void ensureCapacity(int minimumCapacity) {
        if (mHashes.length >= minimumCapacity) return;
        int c = Math.max(minimumCapacity, mHashes.length * 2);
        int[] h = new int[c];
        Object[] k = new Object[c];
        Object[] v = new Object[c];
        System.arraycopy(mHashes, 0, h, 0, mSize);
        System.arraycopy(mKeys, 0, k, 0, mSize);
        System.arraycopy(mValues, 0, v, 0, mSize);
        mHashes = h;
        mKeys = k;
        mValues = v;
    }

    public int indexOfKey(Object key) {
        int h = hash(key);
        for (int i = 0; i < mSize; i++) {
            if (mHashes[i] == h) {
                Object k = mKeys[i];
                if (k == key || (key != null && key.equals(k))) return i;
            }
        }
        return -1;
    }

    public int indexOfValue(Object value) {
        for (int i = 0; i < mSize; i++) {
            Object v = mValues[i];
            if (v == value || (value != null && value.equals(v))) return i;
        }
        return -1;
    }

    public boolean containsKey(Object key) {
        return indexOfKey(key) >= 0;
    }

    public boolean containsValue(Object value) {
        return indexOfValue(value) >= 0;
    }

    @SuppressWarnings("unchecked")
    public V get(Object key) {
        int i = indexOfKey(key);
        return i >= 0 ? (V) mValues[i] : null;
    }

    @SuppressWarnings("unchecked")
    public K keyAt(int index) {
        checkIndex(index);
        return (K) mKeys[index];
    }

    @SuppressWarnings("unchecked")
    public V valueAt(int index) {
        checkIndex(index);
        return (V) mValues[index];
    }

    @SuppressWarnings("unchecked")
    public V setValueAt(int index, V value) {
        checkIndex(index);
        V old = (V) mValues[index];
        mValues[index] = value;
        return old;
    }

    public boolean isEmpty() {
        return mSize <= 0;
    }

    @SuppressWarnings("unchecked")
    public V put(K key, V value) {
        int i = indexOfKey(key);
        if (i >= 0) {
            V old = (V) mValues[i];
            mValues[i] = value;
            return old;
        }
        ensureCapacity(mSize + 1);
        mHashes[mSize] = hash(key);
        mKeys[mSize] = key;
        mValues[mSize] = value;
        mSize++;
        return null;
    }

    public void append(K key, V value) {
        put(key, value);
    }

    public void putAll(ArrayMap<? extends K, ? extends V> array) {
        ensureCapacity(mSize + array.mSize);
        for (int i = 0; i < array.mSize; i++) put(array.keyAt(i), array.valueAt(i));
    }

    public void putAll(Map<? extends K, ? extends V> map) {
        ensureCapacity(mSize + map.size());
        for (Map.Entry<? extends K, ? extends V> e : map.entrySet()) put(e.getKey(), e.getValue());
    }

    public V remove(Object key) {
        int i = indexOfKey(key);
        return i >= 0 ? removeAt(i) : null;
    }

    @SuppressWarnings("unchecked")
    public V removeAt(int index) {
        checkIndex(index);
        V old = (V) mValues[index];
        int move = mSize - index - 1;
        if (move > 0) {
            System.arraycopy(mHashes, index + 1, mHashes, index, move);
            System.arraycopy(mKeys, index + 1, mKeys, index, move);
            System.arraycopy(mValues, index + 1, mValues, index, move);
        }
        mSize--;
        mKeys[mSize] = null;
        mValues[mSize] = null;
        return old;
    }

    public int size() {
        return mSize;
    }

    public boolean containsAll(Collection<?> collection) {
        for (Object o : collection) if (!containsKey(o)) return false;
        return true;
    }

    public boolean removeAll(Collection<?> collection) {
        int old = mSize;
        for (Object o : collection) remove(o);
        return old != mSize;
    }

    public boolean retainAll(Collection<?> collection) {
        int old = mSize;
        for (int i = mSize - 1; i >= 0; i--) if (!collection.contains(mKeys[i])) removeAt(i);
        return old != mSize;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= mSize) throw new ArrayIndexOutOfBoundsException(index);
    }

    // ---- views ----

    private abstract class IndexIterator<T> implements Iterator<T> {
        int mIndex = 0;
        boolean mCanRemove = false;

        public boolean hasNext() {
            return mIndex < mSize;
        }

        public T next() {
            if (mIndex >= mSize) throw new NoSuchElementException();
            T t = elementAt(mIndex++);
            mCanRemove = true;
            return t;
        }

        public void remove() {
            if (!mCanRemove) throw new IllegalStateException();
            removeAt(--mIndex);
            mCanRemove = false;
        }

        abstract T elementAt(int i);
    }

    private final class Entry implements Map.Entry<K, V> {
        final int mIdx;

        Entry(int idx) {
            mIdx = idx;
        }

        public K getKey() {
            return keyAt(mIdx);
        }

        public V getValue() {
            return valueAt(mIdx);
        }

        public V setValue(V v) {
            return setValueAt(mIdx, v);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            Object k = getKey(), v = getValue();
            return (k == null ? e.getKey() == null : k.equals(e.getKey()))
                    && (v == null ? e.getValue() == null : v.equals(e.getValue()));
        }

        @Override
        public int hashCode() {
            Object k = getKey(), v = getValue();
            return (k == null ? 0 : k.hashCode()) ^ (v == null ? 0 : v.hashCode());
        }

        @Override
        public String toString() {
            return getKey() + "=" + getValue();
        }
    }

    public Set<Map.Entry<K, V>> entrySet() {
        return new AbstractSet<Map.Entry<K, V>>() {
            public Iterator<Map.Entry<K, V>> iterator() {
                return new IndexIterator<Map.Entry<K, V>>() {
                    Map.Entry<K, V> elementAt(int i) {
                        return new Entry(i);
                    }
                };
            }

            public int size() {
                return mSize;
            }

            public void clear() {
                ArrayMap.this.clear();
            }
        };
    }

    public Set<K> keySet() {
        return new AbstractSet<K>() {
            public Iterator<K> iterator() {
                return new IndexIterator<K>() {
                    K elementAt(int i) {
                        return keyAt(i);
                    }
                };
            }

            public int size() {
                return mSize;
            }

            public boolean contains(Object o) {
                return containsKey(o);
            }

            public boolean remove(Object o) {
                int i = indexOfKey(o);
                if (i < 0) return false;
                removeAt(i);
                return true;
            }

            public void clear() {
                ArrayMap.this.clear();
            }
        };
    }

    public Collection<V> values() {
        return new AbstractCollection<V>() {
            public Iterator<V> iterator() {
                return new IndexIterator<V>() {
                    V elementAt(int i) {
                        return valueAt(i);
                    }
                };
            }

            public int size() {
                return mSize;
            }

            public boolean contains(Object o) {
                return containsValue(o);
            }

            public void clear() {
                ArrayMap.this.clear();
            }
        };
    }

    // ---- Object ----

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof Map)) return false;
        Map<?, ?> map = (Map<?, ?>) object;
        if (size() != map.size()) return false;
        try {
            for (int i = 0; i < mSize; i++) {
                Object key = keyAt(i), mine = valueAt(i), theirs = map.get(key);
                if (mine == null) {
                    if (theirs != null || !map.containsKey(key)) return false;
                } else if (!mine.equals(theirs)) {
                    return false;
                }
            }
        } catch (NullPointerException ignored) {
            return false;
        } catch (ClassCastException ignored) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (int i = 0; i < mSize; i++) {
            Object v = mValues[i];
            result += mHashes[i] ^ (v == null ? 0 : v.hashCode());
        }
        return result;
    }

    @Override
    public String toString() {
        if (isEmpty()) return "{}";
        StringBuilder b = new StringBuilder(mSize * 28);
        b.append('{');
        for (int i = 0; i < mSize; i++) {
            if (i > 0) b.append(", ");
            Object k = keyAt(i), v = valueAt(i);
            b.append(k == this ? "(this Map)" : k).append('=').append(v == this ? "(this Map)" : v);
        }
        return b.append('}').toString();
    }
}
