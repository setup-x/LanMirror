package com.lanmirror.app;

/** Unique, nonnegative codec PTS preserving source spacing unless duplicate/backwards; zero reserved for CSD. */
public final class SmoothCodecClock {
    private long last=-1;
    public long next(long sourcePtsUs){
        if(last==Long.MAX_VALUE)throw new IllegalStateException("Codec PTS exhausted");
        long minimum=Math.max(1,last+1);
        last=Math.max(sourcePtsUs,minimum);return last;
    }
    public void reset(){last=-1;}
}
