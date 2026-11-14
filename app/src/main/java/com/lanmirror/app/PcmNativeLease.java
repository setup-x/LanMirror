package com.lanmirror.app;

import java.util.concurrent.atomic.AtomicBoolean;

/** Independent process-wide instance per native role; only confirmed native cleanup releases a ticket. */
public final class PcmNativeLease {
    private final AtomicBoolean occupied=new AtomicBoolean();
    public final class Ticket {
        private final AtomicBoolean returned=new AtomicBoolean();
        private Ticket(){}
        public void releaseAfterNative(boolean confirmed){if(confirmed&&returned.compareAndSet(false,true))occupied.set(false);}
    }
    public Ticket tryAcquire(){return occupied.compareAndSet(false,true)?new Ticket():null;}
    public boolean occupied(){return occupied.get();}
}
