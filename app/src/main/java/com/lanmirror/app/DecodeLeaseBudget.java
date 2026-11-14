package com.lanmirror.app;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide multi-stream native budget may outlive one UI/receiver instance, but holds no UI objects. */
public final class DecodeLeaseBudget {
    private final Semaphore permits;
    public DecodeLeaseBudget(int maximum){if(maximum<1)throw new IllegalArgumentException("Positive native limit");permits=new Semaphore(maximum);}
    public Lease tryAcquire(){return permits.tryAcquire()?new Lease():null;}
    public int available(){return permits.availablePermits();}
    public final class Lease {
        private final AtomicBoolean returned=new AtomicBoolean();
        private Lease(){}
        /** Cancellation alone is insufficient; caller must prove decoder owner termination first. */
        public boolean releaseIfTerminated(boolean terminated){if(!terminated)return false;if(returned.compareAndSet(false,true)){permits.release();return true;}return false;}
    }
}
