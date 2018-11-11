package org.apache.tomcat.util.threads;

import org.jctools.queues.MpmcArrayQueue;

import java.util.function.Supplier;

/**
 * Created by ${good-yy} on 2018/11/6.
 */
public class ThreadSharedObjectPool<T>{

    private final MpmcArrayQueue<T> pool;
    private final Supplier<T> supplier;
    private static final int DEFAULT_SIZE = Runtime.getRuntime().availableProcessors() * 8;

    protected T initialValue() {
        return null;
    }

    public T get(){
        T poll = pool.poll();
        if (poll != null) {
            return poll;
        } else {
            return supplier.get();
        }
    }

    public void recycle(T t) {
        pool.offer(t);
    }


    public ThreadSharedObjectPool(int initSize, Supplier<T> supplier) {
        if (supplier == null) {
            this.supplier = new Supplier<T>() {
                @Override
                public T get() {
                    return initialValue();
                }
            };
        } else {
            this.supplier = supplier;
        }
        this.pool = new MpmcArrayQueue<T>(initSize);
        for (int i = 0; i < pool.size(); i++) {
            T t = supplier.get();
            if (t == null) {
                throw new IllegalArgumentException();
            }
            pool.offer(t);
        }
    }

    public ThreadSharedObjectPool(Supplier<T> supplier) {
        this(DEFAULT_SIZE, supplier);
    }

    public ThreadSharedObjectPool() {
        this(DEFAULT_SIZE,null);
    }

}
