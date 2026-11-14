package com.lanmirror.app;

/** Pure geometry only: never attaches, hides, measures or changes the lifetime of a Surface.
 * Bounds include each tile's header. Sources are indexed by physical slot, activeSlots by stable
 * session order. Public Plan arrays are owned snapshots and MUST be treated as read-only;
 * copyBounds()/copyShown() are available to callers that need mutable array containers.
 */
public final class AdaptiveLayoutPolicy {
    private AdaptiveLayoutPolicy() {}

    public static final class Rect {
        public final int left, top, right, bottom;
        public Rect(int left, int top, int right, int bottom) {
            if (left < 0 || top < 0 || right < left || bottom < top)
                throw new IllegalArgumentException("Invalid rectangle");
            this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
        public int width() { return right-left; }
        public int height() { return bottom-top; }
        @Override public String toString() { return "["+left+","+top+","+right+","+bottom+"]"; }
    }

    public static final class Plan {
        public final Rect[] bounds;
        public final boolean[] shown;
        public final int focusedSlot;
        public final String kind;
        private Plan(Rect[] bounds, boolean[] shown, int focusedSlot, String kind) {
            this.bounds=bounds.clone(); this.shown=shown.clone();
            this.focusedSlot=focusedSlot; this.kind=kind;
        }
        public Rect[] copyBounds() { return bounds.clone(); }
        public boolean[] copyShown() { return shown.clone(); }
    }

    /**
     * gap requests BOTH exterior padding and inter-tile gaps. They are capped independently when
     * space is tight. At least one exterior pixel is reserved for parked tiles wherever feasible.
     * Parked bounds are (0,0,1,1) for a positive viewport, otherwise all-zero, and shown=false.
     * With insufficient space for a requested topology (e.g. a one-pixel-wide 2x2 grid), only the
     * earliest active slot is shown. The 1x1 viewport cannot separate its single visible pixel
     * from the parked pixel; this is an unavoidable geometric exception, not a visibility claim.
     * A stale/out-of-range focus is ignored. Invalid dimensions/slot arrays throw IllegalArgumentException.
     * Invalid source dimensions fall back to 16:9. A header taller than its tile leaves score zero.
     */
    public static Plan arrange(int width, int height, int gap, int headerHeight,
                               int[] activeSlots, int[] sourceWidths, int[] sourceHeights,
                               int focusSlot) {
        if(width<0 || height<0 || gap<0 || headerHeight<0)
            throw new IllegalArgumentException("Viewport, gap and header must be nonnegative");
        if(activeSlots==null || activeSlots.length>4 || sourceWidths==null || sourceWidths.length!=4
                || sourceHeights==null || sourceHeights.length!=4)
            throw new IllegalArgumentException("Four source dimensions and up to four active slots required");
        int[] active=activeSlots.clone(), sw=sourceWidths.clone(), sh=sourceHeights.clone();
        boolean[] used=new boolean[4]; int focus=-1;
        for(int slot:active) {
            if(slot<0 || slot>=4 || used[slot])throw new IllegalArgumentException("Active slots must be unique 0..3");
            used[slot]=true;
            if(slot==focusSlot)focus=slot;
        }
        Rect parked=width>0 && height>0 ? new Rect(0,0,1,1) : new Rect(0,0,0,0);
        if(width==0 || height==0)return plan(null, active, parked, focus, focus>=0?"focus":"empty");
        int count=active.length;
        if(focus>=0) return single(width,height,gap,parked,focus,focus,"focus");
        if(count==0) return single(width,height,gap,parked,0,-1,"empty");
        if(count==1) return single(width,height,gap,parked,active[0],-1,"single");

        Rect area=content(width,height,gap,2,count<4);
        Rect[] a, b;
        String kind;
        if(count==2) {
            a=splitTwo(area,gap,true); b=splitTwo(area,gap,false);
            boolean first=choose(a,b,active,sw,sh,headerHeight,width>=height);
            Rect[] selected=first?a:b;
            if(selected==null)return single(width,height,gap,parked,active[0],-1,"single");
            kind=first?"left-right":"top-bottom";
            return plan(selected,active,parked,-1,kind);
        }
        if(count==3) {
            a=hero(area,gap,true); b=hero(area,gap,false);
            boolean first=choose(a,b,active,sw,sh,headerHeight,width>=height);
            Rect[] selected=first?a:b;
            if(selected==null)return single(width,height,gap,parked,active[0],-1,"single");
            kind=first?"hero-left":"hero-top";
            return plan(selected,active,parked,-1,kind);
        }
        Rect[] grid=grid(area,gap);
        if(grid==null)return single(width,height,gap,parked,active[0],-1,"single");
        return plan(grid,active,parked,-1,"grid");
    }

    private static Plan single(int width,int height,int gap,Rect parked,int slot,int focus,String kind) {
        return plan(new Rect[]{content(width,height,gap,1,true)},new int[]{slot},parked,focus,kind);
    }
    private static Plan plan(Rect[] panes,int[] slots,Rect parked,int focus,String kind) {
        Rect[] bounds={parked,parked,parked,parked}; boolean[] shown=new boolean[4];
        if(panes!=null)for(int i=0;i<panes.length;i++){bounds[slots[i]]=panes[i];shown[slots[i]]=panes[i].width()>0&&panes[i].height()>0;}
        return new Plan(bounds,shown,focus,kind);
    }
    private static Rect content(int width,int height,int gap,int minimum,boolean reservePark) {
        int wanted=Math.max(1,gap);
        int px=Math.min(wanted,Math.max(0,(width-minimum)/2));
        int py=Math.min(wanted,Math.max(0,(height-minimum)/2));
        int left=px, top=py, right=width-px, bottom=height-py;
        // Symmetric padding may round to zero in a tiny viewport. An asymmetric spare pixel
        // still separates the parking location, possibly requiring single-slot degradation.
        if(reservePark && left==0 && top==0) {
            if(width>1)left=1;
            else if(height>1)top=1;
        }
        return new Rect(left,top,right,bottom);
    }
    private static Rect[] splitTwo(Rect r,int requested,boolean horizontal) {
        int axis=horizontal?r.width():r.height();if(axis<2)return null;
        int g=Math.min(requested,axis-2), first=(axis-g)/2;
        if(horizontal)return new Rect[]{new Rect(r.left,r.top,r.left+first,r.bottom),new Rect(r.left+first+g,r.top,r.right,r.bottom)};
        return new Rect[]{new Rect(r.left,r.top,r.right,r.top+first),new Rect(r.left,r.top+first+g,r.right,r.bottom)};
    }
    /** Main pane receives 2/3 of the usable primary axis; secondary panes divide the remainder. */
    private static Rect[] hero(Rect r,int requested,boolean left) {
        if(r.width()<2 || r.height()<2)return null;
        int g=Math.min(requested,Math.min(r.width()-2,r.height()-2));
        if(left) {
            int usable=r.width()-g;
            int primary=Math.min(usable-1,Math.max(1,(int)(usable*2L/3L)));
            int split=(r.height()-g)/2, x=r.left+primary+g;
            return new Rect[]{new Rect(r.left,r.top,r.left+primary,r.bottom),
                    new Rect(x,r.top,r.right,r.top+split),new Rect(x,r.top+split+g,r.right,r.bottom)};
        }
        int usable=r.height()-g;
        int primary=Math.min(usable-1,Math.max(1,(int)(usable*2L/3L)));
        int split=(r.width()-g)/2, y=r.top+primary+g;
        return new Rect[]{new Rect(r.left,r.top,r.right,r.top+primary),
                new Rect(r.left,y,r.left+split,r.bottom),new Rect(r.left+split+g,y,r.right,r.bottom)};
    }
    private static Rect[] grid(Rect r,int requested) {
        if(r.width()<2 || r.height()<2)return null;
        int gx=Math.min(requested,r.width()-2),gy=Math.min(requested,r.height()-2);
        int w=(r.width()-gx)/2,h=(r.height()-gy)/2;
        int x=r.left+w+gx,y=r.top+h+gy;
        return new Rect[]{new Rect(r.left,r.top,r.left+w,r.top+h),new Rect(x,r.top,r.right,r.top+h),
                new Rect(r.left,y,r.left+w,r.bottom),new Rect(x,y,r.right,r.bottom)};
    }
    private static boolean choose(Rect[] a,Rect[] b,int[] slots,int[] sw,int[] sh,int header,boolean tieFirst) {
        if(a==null)return false;if(b==null)return true;
        double av=score(a,slots,sw,sh,header),bv=score(b,slots,sw,sh,header);
        double tolerance=Math.max(1e-6,Math.max(av,bv)*1e-9);
        if(av>bv+tolerance)return true;if(bv>av+tolerance)return false;return tieFirst;
    }
    private static double score(Rect[] panes,int[] slots,int[] sw,int[] sh,int header) {
        double total=0;
        for(int i=0;i<panes.length;i++) {
            Rect r=panes[i];int slot=slots[i];
            int sourceW=sw[slot],sourceH=sh[slot];if(sourceW<=0||sourceH<=0){sourceW=16;sourceH=9;}
            double contentW=r.width(),contentH=Math.max(0,r.height()-header);
            double scale=Math.min(contentW/sourceW,contentH/sourceH);
            // Double multiplication avoids int/long overflow even for Integer.MAX_VALUE viewports.
            total+=(sourceW*scale)*(sourceH*scale);
        }
        return total;
    }
}
