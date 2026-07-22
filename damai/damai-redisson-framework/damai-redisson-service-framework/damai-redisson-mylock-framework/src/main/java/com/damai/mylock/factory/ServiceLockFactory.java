package com.damai.mylock.factory;

import com.damai.mylock.core.LockType;
import com.damai.mylock.core.ManageLocker;
import com.damai.mylock.locker.ServiceLocker;

public class ServiceLockFactory {

    private final ManageLocker manageLocker;

    public ServiceLockFactory(ManageLocker manageLocker) {
        this.manageLocker = manageLocker;
    }

    public ServiceLocker getServiceLock(LockType lockType) {
        switch(lockType){
            case Reentrant:
                return manageLocker.getReentrantLocker();
            case Read:
                return manageLocker.getReadLocker();
            case Write:
                return manageLocker.getWriteLocker();
            case Fair:
                return manageLocker.getFairLocker();
            default:
                return null;
        }
    }
}