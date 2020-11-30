package com.unascribed.blockrenderer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.IAsyncReloader;
import net.minecraft.util.Unit;
import net.minecraft.util.Util;

/**
 * Heavily modified version of AsyncReloader
 */
public class AsyncItemRenderer implements IAsyncReloader {

    protected final CompletableFuture<Unit> allAsyncCompleted = new CompletableFuture<>();
    protected final CompletableFuture<List<Void>> resultListFuture;
    private final Set<CompletableFuture<File>> taskSet;
    private final int taskCount;
    private final AtomicInteger asyncScheduled = new AtomicInteger();
    private final AtomicInteger asyncCompleted = new AtomicInteger();

    public AsyncItemRenderer(List<CompletableFuture<File>> futures) {
        taskCount = futures.size();
        taskSet = new HashSet<>(futures);
        asyncScheduled.incrementAndGet();
        CompletableFuture<Unit> alsoWaitedFor = CompletableFuture.completedFuture(Unit.INSTANCE);
        alsoWaitedFor.thenRun(asyncCompleted::incrementAndGet);
        List<CompletableFuture<Void>> list = new ArrayList<>();
        CompletableFuture<?> waitFor = alsoWaitedFor;
        for (CompletableFuture<File> future : futures) {
            final CompletableFuture<?> finalWaitFor = waitFor;
            CompletableFuture<Void> stateFuture = future.thenCompose(backgroundResult -> {
                Minecraft.getInstance().execute(() -> {
                    AsyncItemRenderer.this.taskSet.remove(future);
                    if (AsyncItemRenderer.this.taskSet.isEmpty()) {
                        AsyncItemRenderer.this.allAsyncCompleted.complete(Unit.INSTANCE);
                    }
                });
                return AsyncItemRenderer.this.allAsyncCompleted.thenCombine(finalWaitFor, (unit, instance) -> null);
            });
            list.add(stateFuture);
            waitFor = stateFuture;
        }
        resultListFuture = Util.gather(list);
    }

    @Nonnull
    @Override
    public CompletableFuture<Unit> onceDone() {
        return resultListFuture.thenApply(result -> Unit.INSTANCE);
    }

    @Override
    public float estimateExecutionSpeed() {
        int remaining = taskCount - taskSet.size();
        float completed = 2 * asyncCompleted.get() + remaining;
        float total = 2 * asyncScheduled.get() + taskCount;
        return completed / total;
    }

    @Override
    public boolean asyncPartDone() {
        return allAsyncCompleted.isDone();
    }

    @Override
    public boolean fullyDone() {
        return resultListFuture.isDone();
    }

    @Override
    public void join() {
        if (resultListFuture.isCompletedExceptionally()) {
            resultListFuture.join();
        }
    }
}