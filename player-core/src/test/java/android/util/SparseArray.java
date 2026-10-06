package android.util;

import java.util.Arrays;

/**
 * Минимальная JVM-реализация для unit-тестов: в android.jar для тестов методы — заглушки,
 * а TsExtractor на SparseArray держит всё состояние. Классы тестов стоят в classpath раньше android.jar.
 */
@SuppressWarnings("unchecked")
public class SparseArray<E> {
    private int[] keys = new int[8];
    private Object[] values = new Object[8];
    private int size;

    public SparseArray() {}

    public SparseArray(int capacity) {}

    public E get(int key) {
        return get(key, null);
    }

    public E get(int key, E valueIfKeyNotFound) {
        int i = Arrays.binarySearch(keys, 0, size, key);
        return i < 0 ? valueIfKeyNotFound : (E) values[i];
    }

    public void put(int key, E value) {
        int i = Arrays.binarySearch(keys, 0, size, key);
        if (i >= 0) {
            values[i] = value;
            return;
        }
        i = ~i;
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        System.arraycopy(keys, i, keys, i + 1, size - i);
        System.arraycopy(values, i, values, i + 1, size - i);
        keys[i] = key;
        values[i] = value;
        size++;
    }

    public void append(int key, E value) {
        put(key, value);
    }

    public void remove(int key) {
        delete(key);
    }

    public void delete(int key) {
        int i = Arrays.binarySearch(keys, 0, size, key);
        if (i < 0) return;
        System.arraycopy(keys, i + 1, keys, i, size - i - 1);
        System.arraycopy(values, i + 1, values, i, size - i - 1);
        size--;
        values[size] = null;
    }

    public int size() {
        return size;
    }

    public int keyAt(int index) {
        return keys[index];
    }

    public E valueAt(int index) {
        return (E) values[index];
    }

    public int indexOfKey(int key) {
        int i = Arrays.binarySearch(keys, 0, size, key);
        return i < 0 ? -1 : i;
    }

    public void clear() {
        Arrays.fill(values, null);
        size = 0;
    }
}
