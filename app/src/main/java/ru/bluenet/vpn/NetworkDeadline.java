package ru.bluenet.vpn;
import java.util.concurrent.*;
/** Overall deadlines also stop peers that keep sending a byte before each read timeout. */
final class NetworkDeadline {
    private static final ScheduledThreadPoolExecutor timer=new ScheduledThreadPoolExecutor(2,r->{Thread t=new Thread(r,"bluenet-network-deadline");t.setDaemon(true);return t;});
    static {timer.setRemoveOnCancelPolicy(true);}
    static ScheduledFuture<?> after(Runnable close,int seconds){return timer.schedule(close,seconds,TimeUnit.SECONDS);}
}
