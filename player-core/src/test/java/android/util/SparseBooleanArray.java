package android.util;

/** Минимальная JVM-реализация для unit-тестов. */
public class SparseBooleanArray {
    private final SparseArray<Boolean> map = new SparseArray<>();

    public boolean get(int key) {
        return get(key, false);
    }

    public boolean get(int key, boolean valueIfKeyNotFound) {
        Boolean v = map.get(key);
        return v == null ? valueIfKeyNotFound : v;
    }

    public void put(int key, boolean value) {
        map.put(key, value);
    }

    public void delete(int key) {
        map.delete(key);
    }

    public int size() {
        return map.size();
    }

    public int keyAt(int index) {
        return map.keyAt(index);
    }

    public boolean valueAt(int index) {
        return map.valueAt(index);
    }

    public void clear() {
        map.clear();
    }
}
