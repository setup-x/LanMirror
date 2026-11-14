package com.lanmirror.app;

import java.util.Arrays;
import java.util.Random;

/** Pure JVM geometry/score tests. No Android View or MediaCodec is instantiated. */
public final class AdaptiveLayoutPolicyTest {
    private static long checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private interface Work{void run();}
    private static void bad(Work work,String message){boolean threw=false;try{work.run();}catch(IllegalArgumentException e){threw=true;}check(threw,message);}
    private static int[] widths(){return new int[]{1920,1920,1920,1920};}
    private static int[] heights(){return new int[]{1080,1080,1080,1080};}
    private static AdaptiveLayoutPolicy.Plan arrange(int w,int h,int gap,int header,int[] active,int focus){return AdaptiveLayoutPolicy.arrange(w,h,gap,header,active,widths(),heights(),focus);}
    private static int shown(AdaptiveLayoutPolicy.Plan p){int n=0;for(boolean v:p.shown)if(v)n++;return n;}
    private static boolean overlaps(AdaptiveLayoutPolicy.Rect a,AdaptiveLayoutPolicy.Rect b){return Math.max(a.left,b.left)<Math.min(a.right,b.right)&&Math.max(a.top,b.top)<Math.min(a.bottom,b.bottom);}
    private static void equal(AdaptiveLayoutPolicy.Plan a,AdaptiveLayoutPolicy.Plan b){
        check(a.kind.equals(b.kind)&&a.focusedSlot==b.focusedSlot,"Stable kind/focus");
        for(int s=0;s<4;s++){check(a.shown[s]==b.shown[s],"Stable shown slot");AdaptiveLayoutPolicy.Rect x=a.bounds[s],y=b.bounds[s];check(x.left==y.left&&x.top==y.top&&x.right==y.right&&x.bottom==y.bottom,"Stable bounds");}
    }
    private static void validate(AdaptiveLayoutPolicy.Plan p,int width,int height,int[] active,int focus){
        check(p.bounds.length==4&&p.shown.length==4,"Exactly four physical slots");
        boolean[] used=new boolean[4];boolean focusValid=false;for(int s:active){used[s]=true;if(s==focus)focusValid=true;}
        check(p.focusedSlot==(focusValid?focus:-1),"Stale focus discarded");
        int actual=shown(p);
        if(width==0||height==0)check(actual==0,"Zero viewport has no visible panes");
        else if(focusValid)check(actual==1&&p.shown[focus]&&p.kind.equals("focus"),"Focused active slot only");
        else if(active.length==0)check(actual==1&&p.shown[0]&&p.kind.equals("empty"),"Placeholder is not a new active slot");
        else {
            check(actual==active.length||actual==1,"Full topology or documented earliest-slot fallback");
            if(actual==1)check(p.shown[active[0]],"Tiny fallback keeps oldest active slot");
        }
        for(int slot=0;slot<4;slot++){
            AdaptiveLayoutPolicy.Rect r=p.bounds[slot];
            check(r!=null&&r.left>=0&&r.top>=0&&r.right>=r.left&&r.bottom>=r.top,"Nonnegative valid rectangle");
            check(r.right<=width&&r.bottom<=height,"Every rectangle within viewport");
            if(p.shown[slot]){
                check(r.width()>0&&r.height()>0,"Shown pane positive area");
                check(active.length==0?slot==0:used[slot],"No inactive slot shown (except empty placeholder)");
                for(int other=slot+1;other<4;other++)if(p.shown[other])check(!overlaps(r,p.bounds[other]),"Shown panes never overlap");
            }else{
                int side=width>0&&height>0?1:0;
                check(r.left==0&&r.top==0&&r.right==side&&r.bottom==side,"Exact 1x1 parked rectangle or zero viewport");
                if((long)width*height>1)for(int other=0;other<4;other++)if(p.shown[other])check(!overlaps(r,p.bounds[other]),"Parking is outside every visible pane when geometrically possible");
            }
        }
    }
    public static void main(String[] args){
        basicCases();sourceAndHeaderCases();invalidCases();tinyAndHugeCases();randomCases();
        System.out.println("PASS: "+checks+" adaptive layout geometry assertions (0-4 panes/focus, source-area scoring, stable slot order, input isolation, gaps/header, tiny/overflow/random bounds; NOT Android Surface rendering)");
    }
    private static void basicCases(){
        for(int n=0;n<=4;n++){
            int[] active=Arrays.copyOf(new int[]{1,3,0,2},n);
            for(int[] size:new int[][]{{360,600},{1200,600},{361,601},{601,361}}){
                AdaptiveLayoutPolicy.Plan p=arrange(size[0],size[1],7,29,active,-1);validate(p,size[0],size[1],active,-1);
                check(shown(p)==Math.max(1,n),"Normal viewport never drops a pane");
                if(n==4)check(p.kind.equals("grid"),"Four-way grid");
                if(n==3){AdaptiveLayoutPolicy.Rect main=p.bounds[active[0]],other=p.bounds[active[1]];check((long)main.width()*main.height()>(long)other.width()*other.height(),"Oldest slot gets large hero pane");}
                equal(p,arrange(size[0],size[1],7,29,active,-1));
                for(int focus:active){AdaptiveLayoutPolicy.Plan f=arrange(size[0],size[1],7,29,active,focus);validate(f,size[0],size[1],active,focus);check(f.bounds[focus].left==7&&f.bounds[focus].top==7&&f.bounds[focus].right==size[0]-7&&f.bounds[focus].bottom==size[1]-7,"Focus fills padded content area");}
                equal(p,arrange(size[0],size[1],7,29,active,9));
            }
        }
        int[] active={1,3};AdaptiveLayoutPolicy.Plan identities=arrange(1200,600,8,24,active,-1);check(identities.shown[1]&&identities.shown[3]&&!identities.shown[0]&&!identities.shown[2],"Physical slots need not start at zero");
        AdaptiveLayoutPolicy.Plan hero=arrange(1200,600,8,24,new int[]{3,1,2},-1);check(hero.bounds[3].left==8,"First passed session remains hero, not lowest slot number");
        AdaptiveLayoutPolicy.Plan removed=arrange(1200,600,8,24,new int[]{1,2},3);check(removed.focusedSlot==-1&&shown(removed)==2,"Removing focused session restores automatic layout");
        int[] sw=widths(),sh=heights();int[] before=active.clone(),beforeW=sw.clone(),beforeH=sh.clone();
        AdaptiveLayoutPolicy.Plan frozen=AdaptiveLayoutPolicy.arrange(1000,700,8,20,active,sw,sh,1);
        check(Arrays.equals(active,before)&&Arrays.equals(sw,beforeW)&&Arrays.equals(sh,beforeH),"Inputs untouched");
        active[0]=0;sw[1]=1;sh[1]=Integer.MAX_VALUE;check(frozen.shown[1]&&!frozen.shown[0],"Plan does not retain mutable input arrays");
        AdaptiveLayoutPolicy.Rect[] copy=frozen.copyBounds();copy[1]=new AdaptiveLayoutPolicy.Rect(0,0,0,0);boolean[] visible=frozen.copyShown();visible[1]=false;
        check(frozen.bounds[1].width()>0&&frozen.shown[1],"Clone getters protect plan array containers");
        check(frozen.copyBounds()!=frozen.bounds&&frozen.copyShown()!=frozen.shown,"Clone getters return new arrays");
    }
    private static void sourceAndHeaderCases(){
        int[] active={1,3},pw={1080,1080,1080,1080},ph={1920,1920,1920,1920};
        check(AdaptiveLayoutPolicy.arrange(600,600,8,24,active,pw,ph,-1).kind.equals("left-right"),"Tall videos prefer left-right in square viewport");
        check(arrange(600,600,8,24,active,-1).kind.equals("top-bottom"),"Wide videos prefer top-bottom in square viewport");
        int[] a={1200,1200,1200,1200},b={1000,1000,1000,1000};
        check(AdaptiveLayoutPolicy.arrange(600,600,8,0,active,a,b,-1).kind.equals("top-bottom"),"Without headers stacked content wins");
        check(AdaptiveLayoutPolicy.arrange(600,600,8,100,active,a,b,-1).kind.equals("left-right"),"Headers genuinely affect area score");
        equal(AdaptiveLayoutPolicy.arrange(720,1200,5,37,new int[]{0,1,2},new int[]{0,-1,0,9},new int[]{7,0,-1,9},-1),arrange(720,1200,5,37,new int[]{0,1,2},-1));
        check(arrange(1200,600,8,24,new int[]{3,1,2},-1).kind.equals("hero-left"),"Wide window picks hero-left for landscape sources");
        check(arrange(600,1200,8,24,new int[]{3,1,2},-1).kind.equals("hero-top"),"Tall window picks hero-top for landscape sources");
        check(arrange(1000,500,8,Integer.MAX_VALUE,active,-1).kind.equals("left-right"),"Zero-area tie follows wide orientation");
        check(arrange(500,1000,8,Integer.MAX_VALUE,active,-1).kind.equals("top-bottom"),"Zero-area tie follows tall orientation");
        check(arrange(700,700,8,Integer.MAX_VALUE,new int[]{0,1,2},-1).kind.equals("hero-left"),"Square ties stable, not random");
        Random r=new Random(67123);
        for(int t=0;t<3000;t++){
            int w=200+r.nextInt(1800),h=200+r.nextInt(1800),gap=r.nextInt(30),header=r.nextInt(200);
            int[] sw={1+r.nextInt(4000),1+r.nextInt(4000),1+r.nextInt(4000),1+r.nextInt(4000)},sh={1+r.nextInt(4000),1+r.nextInt(4000),1+r.nextInt(4000),1+r.nextInt(4000)};
            for(int n=2;n<=3;n++){
                int[] slots=Arrays.copyOf(new int[]{3,1,0},n);AdaptiveLayoutPolicy.Plan p=AdaptiveLayoutPolicy.arrange(w,h,gap,header,slots,sw,sh,-1);
                double chosen=actualArea(p,slots,sw,sh,header),other=alternativeArea(p.kind,w,h,gap,header,slots,sw,sh);
                check(chosen+Math.max(1e-4,Math.max(chosen,other)*1e-9)>=other,"Selected layout maximizes actual letterboxed source area within stable tie tolerance");
            }
        }
    }
    private static double videoArea(int w,int h,int sw,int sh,int header){double scale=Math.min((double)w/sw,(double)Math.max(0,h-header)/sh);return (sw*scale)*(sh*scale);}
    private static double actualArea(AdaptiveLayoutPolicy.Plan p,int[] slots,int[] sw,int[] sh,int header){double sum=0;for(int slot:slots){AdaptiveLayoutPolicy.Rect r=p.bounds[slot];sum+=videoArea(r.width(),r.height(),sw[slot],sh[slot],header);}return sum;}
    private static double alternativeArea(String selected,int width,int height,int gap,int header,int[] slots,int[] sw,int[] sh){
        // These scoring checks deliberately use roomy windows; no tiny asymmetric padding is needed.
        int pad=Math.max(1,gap),w=width-2*pad,h=height-2*pad;double total=0;
        if(slots.length==2){boolean horizontal=selected.equals("top-bottom");int extent=horizontal?w:h;int g=Math.min(gap,extent-2),first=(extent-g)/2;
            for(int i=0;i<2;i++){int axis=i==0?first:extent-g-first;total+=videoArea(horizontal?axis:w,horizontal?h:axis,sw[slots[i]],sh[slots[i]],header);}return total;
        }
        boolean left=selected.equals("hero-top");int g=Math.min(gap,Math.min(w-2,h-2));int extent=(left?w:h)-g,main=(int)(extent*2L/3),small=extent-main,other=((left?h:w)-g)/2;
        total+=videoArea(left?main:w,left?h:main,sw[slots[0]],sh[slots[0]],header);
        total+=videoArea(left?small:other,left?other:small,sw[slots[1]],sh[slots[1]],header);
        int last=(left?h:w)-g-other;total+=videoArea(left?small:last,left?last:small,sw[slots[2]],sh[slots[2]],header);return total;
    }
    private static void invalidCases(){
        bad(()->arrange(-1,1,0,0,new int[]{0},-1),"Negative width");bad(()->arrange(1,-1,0,0,new int[]{0},-1),"Negative height");
        bad(()->arrange(1,1,-1,0,new int[]{0},-1),"Negative gap");bad(()->arrange(1,1,0,-1,new int[]{0},-1),"Negative header");
        bad(()->arrange(20,20,1,1,null,-1),"Null active array");bad(()->arrange(20,20,1,1,new int[]{0,1,2,3,0},-1),"Too many active slots");
        bad(()->arrange(20,20,1,1,new int[]{1,1},-1),"Duplicate slots");bad(()->arrange(20,20,1,1,new int[]{-1},-1),"Negative slot");bad(()->arrange(20,20,1,1,new int[]{4},-1),"Out of range slot");
        bad(()->AdaptiveLayoutPolicy.arrange(1,1,0,0,new int[0],null,heights(),-1),"Null source widths");
        bad(()->AdaptiveLayoutPolicy.arrange(1,1,0,0,new int[0],widths(),null,-1),"Null source heights");
        bad(()->AdaptiveLayoutPolicy.arrange(1,1,0,0,new int[0],new int[3],heights(),-1),"Wrong source array length");
        bad(()->new AdaptiveLayoutPolicy.Rect(-1,0,1,1),"Negative rectangle");bad(()->new AdaptiveLayoutPolicy.Rect(3,2,1,4),"Inverted rectangle");
    }
    private static void tinyAndHugeCases(){
        for(int w=0;w<=9;w++)for(int h=0;h<=9;h++)for(int n=0;n<=4;n++)for(int gap:new int[]{0,1,2,8,Integer.MAX_VALUE}){
            int[] slots=Arrays.copyOf(new int[]{2,0,3,1},n);
            for(int focus:new int[]{-1,0,2,9})validate(arrange(w,h,gap,Integer.MAX_VALUE,slots,focus),w,h,slots,focus);
        }
        int m=Integer.MAX_VALUE;
        for(int[] size:new int[][]{{m,m},{m,1},{1,m},{m,2},{2,m},{m,3},{m-1,m-2},{360,600},{1200,600}})
            for(int gap:new int[]{0,1,7,m/2,m})for(int header:new int[]{0,38,m})for(int n=0;n<=4;n++){
                int[] slots=Arrays.copyOf(new int[]{1,3,2,0},n);validate(arrange(size[0],size[1],gap,header,slots,-1),size[0],size[1],slots,-1);
            }
    }
    private static void randomCases(){
        Random random=new Random(0x1737abcdL);
        for(int test=0;test<20000;test++){
            int w=test%7==0?random.nextInt(Integer.MAX_VALUE):random.nextInt(4097),h=test%11==0?random.nextInt(Integer.MAX_VALUE):random.nextInt(4097);
            int gap=test%13==0?Integer.MAX_VALUE:random.nextInt(80),header=test%17==0?Integer.MAX_VALUE:random.nextInt(300);
            int[] pool={0,1,2,3};for(int i=3;i>0;i--){int j=random.nextInt(i+1),v=pool[i];pool[i]=pool[j];pool[j]=v;}
            int[] active=Arrays.copyOf(pool,random.nextInt(5));int focus=random.nextInt(7)-2;
            int[] sw=new int[4],sh=new int[4];for(int s=0;s<4;s++){sw[s]=random.nextBoolean()?random.nextInt(8193)-1:random.nextInt();sh[s]=random.nextBoolean()?random.nextInt(8193)-1:random.nextInt();}
            int[] oldA=active.clone(),oldW=sw.clone(),oldH=sh.clone();
            AdaptiveLayoutPolicy.Plan p=AdaptiveLayoutPolicy.arrange(w,h,gap,header,active,sw,sh,focus);validate(p,w,h,active,focus);
            check(Arrays.equals(oldA,active)&&Arrays.equals(oldW,sw)&&Arrays.equals(oldH,sh),"Random inputs never modified");
            if(test%37==0)equal(p,AdaptiveLayoutPolicy.arrange(w,h,gap,header,active,sw,sh,focus));
        }
    }
}
