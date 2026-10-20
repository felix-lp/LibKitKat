package io.github.felixlp.libkat;

import android.os.Handler;
import android.os.Looper;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

import io.github.felixlp.libkat.core.ApiException;

/** Фоновые задачи с результатом в главном потоке. */
public final class Bg {
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    public interface Job<T> {
        T run() throws Exception;
    }

    public interface Done<T> {
        void done(T result, Exception error);
    }

    private Bg() {
    }

    public static <T> void run(final Job<T> job, final Done<T> done) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                T result = null;
                Exception error = null;
                try {
                    result = job.run();
                } catch (Exception e) {
                    error = e;
                } catch (OutOfMemoryError e) {
                    error = new Exception("Не хватило памяти", e);
                }
                final T r = result;
                final Exception err = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        done.done(r, err);
                    }
                });
            }
        });
    }

    /** Понятное человеку описание ошибки. */
    public static String message(Throwable e) {
        if (e instanceof ApiException) return e.getMessage();
        if (e instanceof UnknownHostException) return "Нет сети или DNS не знает сервер (" + e.getMessage() + ")";
        if (e instanceof SocketTimeoutException) return "Сервер не отвечает";
        if (e instanceof ConnectException) return "Не удалось подключиться: " + e.getMessage();
        if (e instanceof SSLException) return "Ошибка TLS: " + e.getMessage();
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
