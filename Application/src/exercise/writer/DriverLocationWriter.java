package exercise.writer;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.commons.lang.math.RandomUtils;
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
import exercise.util.Constants;
import exercise.util.GeoHash;
import exercise.util.ProducerUtil;
import exercise.util.Utils;

public class DriverLocationWriter {
  private static final Logger log = LoggerFactory.getLogger(DriverLocationWriter.class);
  private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(1);

  public static void main(String[] args) throws Exception {
    BufferedReader br = null;
    try {
      br = new BufferedReader(new FileReader(Constants.DRIVER_LOCATIONS_DATA_SET_FILE_PATH));
    } catch (FileNotFoundException e) {
      System.out.println("DataSet Not Found");
      e.printStackTrace();
    }
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
        log.info(String.format("%d puts have completed after 20 seconds", done));
      }
    }, 1, Constants.DRIVER_LOCATION_UPDATE_INTERVAL, TimeUnit.SECONDS);

    boolean condition = true;
    while (condition) {
      String line;
      ByteBuffer data = null;
      List<String> locationUpdates = new ArrayList<>();
      int driverCount = Constants.MIN_DRIVER_COUNT
          + RandomUtils.nextInt() % (Constants.MAX_DRIVER_COUNT - Constants.MIN_DRIVER_COUNT);
      int offSet = RandomUtils.nextInt();
      for (int i = 0; i < driverCount; i++) {
        int driverId = (i + offSet) % Constants.MAX_DRIVER_COUNT;
        try {
          if ((line = br.readLine()) != null) {
            String[] fields = line.split(",");
            double longitude = Double.valueOf(fields[0]);
            double latitude = Double.valueOf(fields[1]);
            String geo = GeoHash.getGeoHashString(latitude, longitude);
            locationUpdates.add(geo + Constants.DELIMITER + driverId);
          } else {
            condition = false;
            break;
          }
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
      try {
        data = ByteBuffer
            .wrap(locationUpdates.stream().collect(Collectors.joining(",")).getBytes("UTF-8"));
      } catch (UnsupportedEncodingException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
      ListenableFuture<UserRecordResult> f =
          producer.addUserRecord(Constants.DRIVER_LOCATION_STREAM_NAME,
              Utils.randomExplicitHashKey(), Utils.randomExplicitHashKey(), data);
      Futures.addCallback(f, callback);
      Thread.sleep(5000);
    }
    producer.flushSync();
    log.info("Waiting for remaining puts to finish...");
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
