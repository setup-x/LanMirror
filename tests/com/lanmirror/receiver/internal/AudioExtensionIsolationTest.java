package com.lanmirror.receiver.internal;

import com.lanmirror.core.api.AudioExtensions;
import com.lanmirror.core.api.Wire;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class AudioExtensionIsolationTest {
    private static final AudioExtensions.ReceiverListener LISTENER=new AudioExtensions.ReceiverListener(){
        public void onSelection(long id,long revision){}
        public void onPlaybackStatus(long id,String text,boolean error){}
    };
    private static final AudioExtensions.Status STATUS=text->{throw new IllegalStateException("host callback");};
    public static void main(String[] args)throws Exception{
        SafeAudioReceiver silent=new SafeAudioReceiver(null,LISTENER);
        check(silent.reserve(null,1,STATUS)==null,"default without audio");
        silent.joined(0,1);silent.toggle(0,1);silent.left(1);silent.close();silent.close();
        SafeAudioReceiver factoryFailure=new SafeAudioReceiver(l->{throw new IllegalStateException("factory");},LISTENER);
        check(factoryFailure.reserve(null,1,STATUS)==null,"factory failure isolated");factoryFailure.close();
        for(int mode=1;mode<=3;mode++){
            FakeReceiver fake=new FakeReceiver();fake.reserveFailure=mode;
            SafeAudioReceiver receiver=new SafeAudioReceiver(l->fake,LISTENER);
            check(receiver.reserve(null,1,STATUS)==null,"reserve/getter failure isolated");receiver.close();
            if(mode==3)check(fake.endpoint.closes==1,"failed getter closes reserved endpoint");
        }
        FakeReceiver fake=new FakeReceiver();fake.endpoint.failStart=true;fake.endpoint.failClose=true;fake.failClose=true;
        SafeAudioReceiver receiver=new SafeAudioReceiver(l->fake,LISTENER);
        AudioExtensions.Endpoint endpoint=receiver.reserve(null,1,STATUS);
        check(endpoint.port()==1234&&endpoint.tokenHigh()==1&&endpoint.tokenLow()==2,"grant preserved");
        check(fake.endpoint.starts==0,"reserve never starts before approval/clock");
        endpoint.start(null);endpoint.start(null);endpoint.close();receiver.close();receiver.close();
        check(fake.endpoint.starts==1&&fake.endpoint.closes==1&&fake.closes==1,"start/close failure isolated and idempotent");
        for(int action=1;action<=3;action++){
            FakeReceiver runtime=new FakeReceiver();runtime.runtimeFailure=action;
            SafeAudioReceiver safe=new SafeAudioReceiver(l->runtime,LISTENER);safe.reserve(null,1,STATUS);
            if(action==1)safe.joined(0,1);else if(action==2)safe.left(1);else safe.toggle(0,1);
            check(runtime.closes==1&&runtime.endpoint.closes==1,"runtime failure closes only audio");
        }
        FakeReceiver cancelled=new FakeReceiver();SafeAudioReceiver safe=new SafeAudioReceiver(l->cancelled,LISTENER);
        AudioExtensions.Endpoint cancelledEndpoint=safe.reserve(null,1,STATUS);cancelledEndpoint.close();cancelledEndpoint.start(null);safe.close();
        check(cancelled.endpoint.starts==0&&cancelled.endpoint.closes==1,"cancel prevents later start");
        reserveCloseRace();startCloseRace();
        System.out.println("AudioExtensionIsolationTest OK");
    }
    private static void reserveCloseRace()throws Exception{
        FakeReceiver fake=new FakeReceiver();fake.entered=new CountDownLatch(1);fake.release=new CountDownLatch(1);
        SafeAudioReceiver safe=new SafeAudioReceiver(l->fake,LISTENER);AtomicReference<AudioExtensions.Endpoint> result=new AtomicReference<>();
        Thread reserving=new Thread(()->result.set(safe.reserve(null,1,STATUS)));reserving.start();await(fake.entered);
        safe.close();fake.release.countDown();reserving.join(2000);
        check(!reserving.isAlive()&&result.get()==null&&fake.endpoint.closes==1,"late reservation cleaned after close");
    }
    private static void startCloseRace()throws Exception{
        FakeReceiver fake=new FakeReceiver();fake.endpoint.entered=new CountDownLatch(1);fake.endpoint.release=new CountDownLatch(1);
        SafeAudioReceiver safe=new SafeAudioReceiver(l->fake,LISTENER);AudioExtensions.Endpoint endpoint=safe.reserve(null,1,STATUS);
        Thread starting=new Thread(()->endpoint.start(null));starting.start();await(fake.endpoint.entered);
        Thread closing=new Thread(safe::close);closing.start();closing.join(1000);
        boolean nonblocking=!closing.isAlive();fake.endpoint.release.countDown();starting.join(2000);closing.join(2000);
        check(nonblocking&&fake.endpoint.closes==1,"close does not wait for extension start");
    }
    private static void await(CountDownLatch latch){try{check(latch.await(3,TimeUnit.SECONDS),"latch timeout");}catch(InterruptedException e){throw new AssertionError(e);}}
    private static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
    private static final class FakeReceiver implements AudioExtensions.Receiver {
        final FakeEndpoint endpoint=new FakeEndpoint();int reserveFailure,runtimeFailure,closes;boolean failClose;
        CountDownLatch entered,release;
        public AudioExtensions.Endpoint reserve(InetAddress address,long id,AudioExtensions.Status status)throws IOException{
            if(entered!=null){entered.countDown();await(release);}
            if(reserveFailure==1)throw new IOException("reserve");if(reserveFailure==2)throw new IllegalStateException("reserve");
            endpoint.failGetter=reserveFailure==3;return endpoint;
        }
        public void joined(int slot,long id){if(runtimeFailure==1)throw new IllegalStateException("joined");}
        public void left(long id){if(runtimeFailure==2)throw new IllegalStateException("left");}
        public void toggle(int slot,long id){if(runtimeFailure==3)throw new IllegalStateException("toggle");}
        public void close(){closes++;if(failClose)throw new IllegalStateException("receiver close");}
    }
    private static final class FakeEndpoint implements AudioExtensions.Endpoint {
        int starts,closes;boolean failGetter,failStart,failClose;CountDownLatch entered,release;
        public int port(){if(failGetter)throw new IllegalStateException("port");return 1234;}
        public long tokenHigh(){return 1;}
        public long tokenLow(){return 2;}
        public void start(Wire.ClockSync clock){starts++;if(entered!=null){entered.countDown();await(release);}if(failStart)throw new IllegalStateException("start");}
        public void close(){closes++;if(failClose)throw new IllegalStateException("endpoint close");}
    }
}
