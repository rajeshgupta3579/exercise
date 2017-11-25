package exercise.writer;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.amazonaws.services.kinesis.producer.Attempt;
import com.amazonaws.services.kinesis.producer.KinesisProducer;
import com.amazonaws.services.kinesis.producer.UserRecordFailedException;
import com.amazonaws.services.kinesis.producer.UserRecordResult;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import exercise.processor.GeoHash;
import exercise.processor.Utils;
import exercise.util.Constants;
import exercise.util.ProducerUtil;

public class IncomingRequestWriter {
  private static final Logger log = LoggerFactory.getLogger(IncomingRequestWriter.class);
  private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(1);

  public static void main(String[] args) throws Exception {
    String dataSetFilePath = "../Datasets/dataset.csv";
    BufferedReader br = null;
    try {
      br = new BufferedReader(new FileReader(dataSetFilePath));
    } catch (FileNotFoundException e) {
      System.out.println("DataSet Not Found");
      e.printStackTrace();
    }
    final BufferedReader finalBr = br;

    final KinesisProducer producer = ProducerUtil.getKinesisProducer();
    final AtomicLong completed = new AtomicLong(0);
    final FutureCallback<UserRecordResult> callback = new FutureCallback<UserRecordResult>() {
      @Override
      public void onFailure(Throwable t) {
        if (t instanceof UserRecordFailedException) {
          Attempt last =
              Iterables.getLast(((UserRecordFailedException) t).getResult().getAttempts());
          log.error(String.format("Record failed to put - %s : %s", last.getErrorCode(),
              last.getErrorMessage()));
        }
        log.error("Exception during put", t);
        System.exit(1);
      }

      @Override
      public void onSuccess(UserRecordResult result) {
        completed.getAndIncrement();
      }
    };

    // This gives us progress updates
    EXECUTOR.scheduleAtFixedRate(new Runnable() {
      @Override
      public void run() {
        long done = completed.get();
        log.info(String.format("%d puts have completed", done));
      }
    }, 1, 1, TimeUnit.SECONDS);

    EXECUTOR.scheduleWithFixedDelay(new Runnable() {

      @Override
      public void run() {

        String line;
        try {
          if ((line = finalBr.readLine()) != null) {
            final String finalLine = line;
            String[] fields = finalLine.split(",");
            double longitude = Double.parseDouble(fields[5]);
            double latitude = Double.parseDouble(fields[6]);
            GeoHash g = new GeoHash(latitude, longitude);
            g.sethashLength(6);
            String geo = g.getGeoHashBase32();
            // System.out.println(geo);
            ByteBuffer data = null;
            try {
              data = ByteBuffer.wrap(geo.getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
              // TODO Auto-generated catch block
              e.printStackTrace();
            }
            ListenableFuture<UserRecordResult> f =
                producer.addUserRecord(Constants.INCOMING_REQUEST_STREAM_NAME,
                    Utils.randomExplicitHashKey(), Utils.randomExplicitHashKey(), data);
            Futures.addCallback(f, callback);
          }
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    }, 0, 1, TimeUnit.MILLISECONDS);
    // EXECUTOR.awaitTermination(10, TimeUnit.SECONDS);
    log.info("Waiting for remaining puts to finish...");
    Thread.sleep(100);
    producer.flushSync();
    log.info("All records complete.");
    producer.destroy();
    try {
      br.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
    log.info("Finished.");
    EXECUTOR.shutdown();
  }
}