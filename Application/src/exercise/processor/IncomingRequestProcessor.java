package exercise.processor;

import java.util.List;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ThrottlingException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.ShutdownReason;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;
import com.amazonaws.services.kinesis.model.Record;
import exercise.util.Constants;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;


public class IncomingRequestProcessor implements IRecordProcessorFactory {
	
  private static final Logger log = LogManager.getLogger(IncomingRequestProcessor.class);
  private static final JedisPool jedisPool =
      new JedisPool(Constants.REDIS_HOST, Constants.REDIS_PORT);

  private class RecordProcessor implements IRecordProcessor {
    private String kinesisShardId;
    private static final long CHECKPOINT_INTERVAL_MILLIS = 60000L; // 1 minute
    private long nextCheckpointTimeInMillis;

    @Override
    public void initialize(String shardId) {
      log.info("Initializing record processor for shard: " + shardId);
      this.kinesisShardId = shardId;
      nextCheckpointTimeInMillis = System.currentTimeMillis() + CHECKPOINT_INTERVAL_MILLIS;
    }

    @Override
    public void processRecords(List<Record> records, IRecordProcessorCheckpointer checkpointer) {
      Jedis jedis = jedisPool.getResource();
      System.out.println("--------------------------------------HERE------------------------------------");

      for (Record r : records) {
        try {
          byte[] b = new byte[r.getData().remaining()];
          r.getData().get(b);
          String geohash = new String(b, "UTF-8");
          incrementCountForGeoHash(Constants.DEMAND_KEY_PREFIX + geohash, jedis);
        } catch (Exception e) {
          log.error("Error parsing record", e);
          System.exit(1);
        }
      }

      if (System.currentTimeMillis() > nextCheckpointTimeInMillis) {
    	  System.out.println("--------------------------------------checkpoint HERE------------------------------------");
        checkpoint(checkpointer);
        nextCheckpointTimeInMillis = System.currentTimeMillis() + CHECKPOINT_INTERVAL_MILLIS;
      }

    }

    private void incrementCountForGeoHash(String geohash, Jedis jedis) {
      String count = jedis.get(geohash);
      if (StringUtils.isEmpty(count)) {
        jedis.set(geohash, "1");
      } else if (StringUtils.isNumeric(count)) {
        Integer countInt = Integer.valueOf(count);
        jedis.set(geohash, String.valueOf(countInt + 1));
      }
      System.out.println("--------------------------------------redis------------------------------------");
      log.debug("Written to Redis");
    }

    @Override
    public void shutdown(IRecordProcessorCheckpointer checkpointer, ShutdownReason reason) {
      log.info(
          "Shutting down record processor for shard: " + kinesisShardId + " , reason: " + reason);
      checkpoint(checkpointer);
    }

    private void checkpoint(IRecordProcessorCheckpointer checkpointer) {
      log.info("Checkpointing shard " + kinesisShardId);
      try {
        checkpointer.checkpoint();
      } catch (ShutdownException se) {
        // Ignore checkpoint if the processor instance has been shutdown (fail over).
        log.info("Caught shutdown exception, skipping checkpoint.", se);
      } catch (ThrottlingException e) {
        // Skip checkpoint when throttled. In practice, consider a backoff and retry policy.
        log.error("Caught throttling exception, skipping checkpoint.", e);
      } catch (InvalidStateException e) {
        // This indicates an issue with the DynamoDB table (check for table, provisioned IOPS).
        log.error(
            "Cannot save checkpoint to the DynamoDB table used by the Amazon Kinesis Client Library.",
            e);
      } catch (Exception e) {
        log.error("Error while trying to checkpoint during ProcessRecords", e);
      }
    }
  }

  @Override
  public IRecordProcessor createProcessor() {
    return this.new RecordProcessor();
  }

  public static void main(String[] args) {
    KinesisClientLibConfiguration config =
        new KinesisClientLibConfiguration(Constants.INCOMING_REQUEST_APPLICATION_NAME,
            Constants.INCOMING_REQUEST_STREAM_NAME, new DefaultAWSCredentialsProviderChain(),
            Constants.INCOMING_REQUEST_APPLICATION_NAME).withRegionName(Constants.REGION)
        .withInitialPositionInStream(InitialPositionInStream.TRIM_HORIZON)
        .withMaxRecords(10);


    final IncomingRequestProcessor consumer = new IncomingRequestProcessor();
    new Worker.Builder().recordProcessorFactory(consumer).config(config).build().run();
  }
}
