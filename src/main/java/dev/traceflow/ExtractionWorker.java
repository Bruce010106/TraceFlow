package dev.traceflow;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Bounded local execution; all durable state remains in PostgreSQL. */
@Component
public class ExtractionWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ExtractionWorker.class);
    private final ExtractionQueue queue;
    private final QueueProperties config;
    private final String workerId="worker-"+UUID.randomUUID();
    private final ConcurrentMap<UUID,Active> active=new ConcurrentHashMap<>();
    private final AtomicBoolean running=new AtomicBoolean();
    private ExecutorService workers;
    private ScheduledExecutorService poller,heartbeats;
    private Semaphore permits;
    private static final class Active {
        final ExtractionQueue.Lease lease;volatile Future<?> future;
        Active(ExtractionQueue.Lease lease){this.lease=lease;}
    }
    public ExtractionWorker(ExtractionQueue queue,QueueProperties config){this.queue=queue;this.config=config;}
    private static ThreadFactory threads(String name){
        return runnable->{var thread=new Thread(runnable,name);thread.setDaemon(true);return thread;};
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start(){
        if(!config.isEnabled()||!running.compareAndSet(false,true))return;
        permits=new Semaphore(config.getConcurrency());
        workers=Executors.newFixedThreadPool(config.getConcurrency(),threads("traceflow-extraction"));
        poller=Executors.newSingleThreadScheduledExecutor(threads("traceflow-queue-poll"));
        heartbeats=Executors.newSingleThreadScheduledExecutor(threads("traceflow-lease-heartbeat"));
        poller.scheduleWithFixedDelay(this::pollSafely,0,config.getPollMs(),TimeUnit.MILLISECONDS);
        heartbeats.scheduleWithFixedDelay(this::heartbeatSafely,config.getHeartbeatMs(),config.getHeartbeatMs(),TimeUnit.MILLISECONDS);
    }
    private synchronized void pollSafely(){
        if(!running.get())return;
        final Semaphore capacity=permits;
        try{
            queue.recoverExpired(20);
            while(running.get()&&capacity.tryAcquire()){
                ExtractionQueue.Lease lease;
                try{
                    var next=queue.claim(workerId);
                    if(next.isEmpty()){capacity.release();break;}
                    lease=next.get();
                }catch(RuntimeException e){capacity.release();throw e;}
                var work=new Active(lease);active.put(lease.jobId(),work);
                try{
                    work.future=workers.submit(()->{
                        try{queue.execute(lease);}
                        catch(RuntimeException e){LOG.warn("Extraction {} left for lease recovery ({})",lease.jobId(),e.getClass().getSimpleName());}
                        finally{active.remove(lease.jobId(),work);capacity.release();}
                    });
                }catch(RejectedExecutionException e){
                    active.remove(lease.jobId(),work);capacity.release();queue.abandon(lease);
                }
            }
        }catch(RuntimeException e){LOG.warn("Queue poll will retry ({})",e.getClass().getSimpleName());}
    }
    private void heartbeatSafely(){
        if(!running.get())return;
        for(var work:active.values()){
            try{
                queue.renew(work.lease); // A busy row can belong to our own finalization. Token guards decide ownership.
            }catch(RuntimeException e){
                // Do not assume ownership after an outage. Completion still checks the database lease.
                LOG.warn("Heartbeat unavailable for {} ({})",work.lease.jobId(),e.getClass().getSimpleName());
            }
        }
    }
    @PreDestroy
    public synchronized void close(){
        if(!running.compareAndSet(true,false))return;
        poller.shutdownNow();heartbeats.shutdownNow();
        for(var work:active.values()){
            try{queue.abandon(work.lease);}catch(RuntimeException e){LOG.warn("Lease {} will expire after shutdown",work.lease.jobId());}
            if(work.future!=null)work.future.cancel(true);
        }
        workers.shutdownNow();
        try{workers.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        active.clear();
    }
}
