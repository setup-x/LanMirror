package com.lanmirror.app;

/** Focus follows a session identity, not just a reusable physical slot. */
public final class AdaptiveFocusState {
    private int slot=-1;
    private long sessionId;
    public void toggle(int slot,long sessionId){
        if(slot<0||slot>=4||sessionId<=0)throw new IllegalArgumentException("Invalid focused session");
        if(this.slot==slot&&this.sessionId==sessionId){clear();return;}
        this.slot=slot;this.sessionId=sessionId;
    }
    public void clear(){slot=-1;sessionId=0;}
    public int resolve(long[] activeSessionIds){
        if(activeSessionIds==null||activeSessionIds.length!=4)throw new IllegalArgumentException("Four physical slots required");
        if(slot>=0&&activeSessionIds[slot]!=sessionId)clear();
        return slot;
    }
}
