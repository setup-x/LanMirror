package com.lanmirror.app;

/** Owner-thread model; LOSS never triggers an automatic re-request. Duck requests pause for this player. */
public final class PcmAudioFocusState {
    public enum State { MUTED,REQUESTABLE,GRANTED,TRANSIENT,LOST,DENIED }
    private State state=State.MUTED;
    public void select(long source){if(source<0)throw new IllegalArgumentException("Negative source");state=source==0?State.MUTED:State.REQUESTABLE;}
    public void requestResult(boolean granted){if(state!=State.REQUESTABLE)throw new IllegalStateException("Focus requested without a new selection");state=granted?State.GRANTED:State.DENIED;}
    public void changed(int change){
        if(state!=State.GRANTED&&state!=State.TRANSIENT)return;
        if(change==1){if(state==State.TRANSIENT)state=State.GRANTED;}
        else if(change==-1)state=State.LOST;
        else if(change==-2||change==-3)state=State.TRANSIENT;
    }
    public State state(){return state;}
    public boolean canPlay(){return state==State.GRANTED;}
}
