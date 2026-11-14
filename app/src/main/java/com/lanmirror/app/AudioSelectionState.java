package com.lanmirror.app;

/** Selection is independent of visual focus. Join order means approval completion, not request id. */
public final class AudioSelectionState {
    private final long[] ids=new long[4],orders=new long[4];
    private long selected,order,revision;
    public synchronized boolean joined(int slot,long id){
        if(slot<0||slot>=4||id<=0)throw new IllegalArgumentException("Invalid audio source");
        if(ids[slot]==id)return false;
        for(int i=0;i<4;i++)if(i!=slot&&ids[i]==id)throw new IllegalArgumentException("Source occupies two slots");
        ids[slot]=id;orders[slot]=++order;selected=id;revision++;return true;
    }
    public synchronized boolean toggle(int slot,long id){
        if(slot<0||slot>=4||ids[slot]!=id||id<=0)return false;
        selected=selected==id?0:id;revision++;return true;
    }
    public synchronized boolean left(long id){
        int slot=-1;for(int i=0;i<4;i++)if(ids[i]==id){slot=i;break;}if(slot<0)return false;
        ids[slot]=orders[slot]=0;
        if(selected==id){selected=0;long latest=0;for(int i=0;i<4;i++)if(orders[i]>latest){latest=orders[i];selected=ids[i];}}
        revision++;return true;
    }
    public synchronized long selected(){return selected;}
    public synchronized long revision(){return revision;}
    public synchronized void clear(){for(int i=0;i<4;i++){ids[i]=orders[i]=0;}selected=0;revision++;}
}
