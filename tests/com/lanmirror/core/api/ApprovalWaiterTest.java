package com.lanmirror.core.api;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Live loopback approval wait and process-budget tests, without Android or native codecs. */
public final class ApprovalWaiterTest {
    private static int checks;private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static final class Pair implements AutoCloseable {
        final ServerSocket server;final Socket client,accepted;
        Pair()throws IOException{server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());client=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());accepted=server.accept();client.setSoTimeout(2000);accepted.setSoTimeout(2000);}
        public void close()throws IOException{client.close();accepted.close();server.close();}
    }
    private static AdmissionSlots gate(){AdmissionSlots gate=new AdmissionSlots(4,8,12);gate.register(1);gate.beginPending(1,System.nanoTime());return gate;}
    public static void main(String[]args)throws Exception {
        try(Pair p=new Pair()){
            AdmissionSlots g=gate();AtomicReference<Exception> error=new AtomicReference<>();Thread t=new Thread(()->{try{ApprovalWaiter.await(new DataInputStream(p.accepted.getInputStream()),p.accepted,g,1,()->false);}catch(Exception e){error.set(e);}});t.start();p.client.close();t.join(1500);
            check(!t.isAlive()&&error.get() instanceof EOFException,"cancelled client EOF removes pending wait promptly");g.release(1);check(g.pendingCount()==0,"EOF request cleanup frees dialog budget");
        }
        try(Pair p=new Pair()){
            AdmissionSlots g=gate();p.client.getOutputStream().write(99);p.client.getOutputStream().flush();boolean premature=false;try{ApprovalWaiter.await(new DataInputStream(p.accepted.getInputStream()),p.accepted,g,1,()->false);}catch(IOException e){premature=e.getMessage().contains("批准前");}
            check(premature,"media/next handshake before approval is not consumed as a valid stream");g.release(1);
        }
        try(Pair p=new Pair()){
            AdmissionSlots g=new AdmissionSlots(4,8,12);g.register(1);g.beginPending(1,System.nanoTime()-AdmissionSlots.APPROVAL_NS+20_000_000L);AdmissionSlots.Snapshot s=ApprovalWaiter.await(new DataInputStream(p.accepted.getInputStream()),p.accepted,g,1,()->false);
            check(s.phase==AdmissionSlots.Phase.REJECTED&&s.reason.contains("超时"),"absolute approval deadline independent of reader activity");check(g.pendingCount()==0,"timed out dialog removed");g.release(1);
        }
        for(boolean allow:new boolean[]{false,true})for(boolean udp:new boolean[]{false,true})try(Pair p=new Pair()){
            AtomicReference<Throwable> serverError=new AtomicReference<>();AtomicReference<String> answer=new AtomicReference<>();CountDownLatch pending=new CountDownLatch(1);AdmissionSlots g=new AdmissionSlots(4,8,12);g.register(1);
            Thread server=new Thread(()->{try{
                DataInputStream in=new DataInputStream(p.accepted.getInputStream());DataOutputStream out=new DataOutputStream(p.accepted.getOutputStream());ApprovalProtocol.Request request=ApprovalProtocol.readRequest(in);if(!request.name.equals("手机A")||request.udp!=udp)throw new AssertionError("request mismatch");ApprovalProtocol.writeWaiting(out);g.beginPending(1,System.nanoTime());pending.countDown();AdmissionSlots.Snapshot s=ApprovalWaiter.await(in,p.accepted,g,1,()->false);
                if(s.phase==AdmissionSlots.Phase.PENDING)s=g.admit(1,new boolean[]{true,true,true,true},System.nanoTime());
                if(s.phase==AdmissionSlots.Phase.ACTIVE){ApprovalProtocol.writeAccepted(out,"123456");int trailing=in.readInt();if(trailing!=0x12345678)throw new AssertionError("approved handshake byte stolen by waiter");}
                else ApprovalProtocol.writeRejected(out,s.reason);
            }catch(Throwable e){serverError.set(e);}finally{g.release(1);}});server.start();
            Thread client=new Thread(()->{try{DataOutputStream out=new DataOutputStream(p.client.getOutputStream());String pin=ApprovalProtocol.clientRequest(new DataInputStream(p.client.getInputStream()),out,"手机A",udp);answer.set(pin);out.writeInt(0x12345678);out.flush();}catch(IOException e){answer.set(e.getMessage());}});client.start();
            check(pending.await(1500,TimeUnit.MILLISECONDS),"request reaches explicit WAIT before decision");check(g.choose(1,allow),"decision accepted without socket write on test/UI thread");server.join(2000);client.join(2000);
            check(!server.isAlive()&&!client.isAlive()&&serverError.get()==null,"same socket approval flow completed");check(allow?"123456".equals(answer.get()):answer.get().contains("拒绝"),"allow returns ephemeral PIN or readable rejection");check(g.acceptedCount()==0&&g.activeCount()==0,"normal approval peer cleanup releases all admission resources");
        }
        try(Pair p=new Pair()){
            AdmissionSlots g=gate();AtomicBoolean stopped=new AtomicBoolean();AtomicReference<Throwable> result=new AtomicReference<>();Thread thread=new Thread(()->{try{ApprovalWaiter.await(new DataInputStream(p.accepted.getInputStream()),p.accepted,g,1,stopped::get);}catch(Throwable e){result.set(e);}});thread.start();stopped.set(true);g.close();p.accepted.close();thread.join(1500);check(!thread.isAlive()&&result.get() instanceof IOException,"stop closes wait immediately without approval or native work");g.release(1);
        }
        DecodeLeaseBudget global=new DecodeLeaseBudget(4);DecodeLeaseBudget.Lease[] oldReceiver=new DecodeLeaseBudget.Lease[4];for(int i=0;i<4;i++){oldReceiver[i]=global.tryAcquire();check(oldReceiver[i]!=null,"old manager obtains one native budget");}
        check(global.available()==0&&global.tryAcquire()==null,"new manager cannot bypass still-held old decoder resources");
        for(DecodeLeaseBudget.Lease lease:oldReceiver)check(!lease.releaseIfTerminated(false),"logical cancel without native termination must not return capacity");
        check(global.tryAcquire()==null,"all quarantined decoders prevent new allocation");check(oldReceiver[0].releaseIfTerminated(true),"observed owner termination permits one reuse");check(!oldReceiver[0].releaseIfTerminated(true),"duplicate native cleanup cannot increase capacity");DecodeLeaseBudget.Lease replacement=global.tryAcquire();check(replacement!=null&&global.available()==0,"new manager reuses only released native lease");replacement.releaseIfTerminated(true);for(int i=1;i<4;i++)oldReceiver[i].releaseIfTerminated(true);check(global.available()==4,"all terminated owners restore exact global cap");
        System.out.println("PASS: "+checks+" approval wait/native lease assertions (real localhost allow/deny, EOF, premature media, timeout, same stream continuation, stop and cross-manager resource quarantine; NOT codec FPS)");
    }
}
