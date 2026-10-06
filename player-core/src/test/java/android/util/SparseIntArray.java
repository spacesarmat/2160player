package android.util;

/** Минимальная JVM-реализация для unit-тестов. */
public class SparseIntArray {
    private final SparseArray<Integer> map = new SparseArray<>();

    public int get(int key) {
        return get(key, 0);
    }

    public int get(int key, int valueIfKeyNotFound) {
        Integer v = map.get(key);
        return v == null ? valueIfKeyNotFound : v;
    }

    public void put(int key, int value) {
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

    public int valueAt(int index) {
        return map.valueAt(index);
    }

    public void clear() {
        map.clear();
    }
}
