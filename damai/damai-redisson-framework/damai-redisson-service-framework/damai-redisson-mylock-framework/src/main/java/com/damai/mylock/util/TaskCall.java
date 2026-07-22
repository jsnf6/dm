package com.damai.mylock.util;

@FunctionalInterface
public interface TaskCall<T> {
    T call();
   }
