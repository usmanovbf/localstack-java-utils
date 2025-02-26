package cloud.localstack.deprecated;

import cloud.localstack.CommonUtils;
import cloud.localstack.awssdkv1.LocalTestUtilSDKV1;
import cloud.localstack.utils.LocalTestUtil;
import cloud.localstack.awssdkv1.TestUtils;
import cloud.localstack.awssdkv1.sample.KinesisLambdaHandler;
import cloud.localstack.awssdkv1.sample.S3Sample;
import cloud.localstack.awssdkv1.sample.SQSLambdaHandler;

import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.model.ListStreamsResult;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.model.CreateEventSourceMappingRequest;
import com.amazonaws.services.lambda.model.CreateFunctionRequest;
import com.amazonaws.services.lambda.model.ListFunctionsResult;
import com.amazonaws.services.lambda.model.Runtime;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.DeleteQueueRequest;
import com.amazonaws.services.sqs.model.GetQueueAttributesRequest;
import com.amazonaws.services.sqs.model.GetQueueAttributesResult;
import com.amazonaws.services.sqs.model.ListQueuesResult;
import com.amazonaws.services.sqs.model.QueueAttributeName;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageResult;
import io.thundra.jexter.junit4.core.envvar.EnvironmentVariableSandboxRule;
import io.thundra.jexter.junit5.core.envvar.EnvironmentVariableSandbox;
import org.assertj.core.api.Assertions;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static cloud.localstack.awssdkv1.TestUtils.TEST_CREDENTIALS;

/**
 * Simple class to test basic functionality and interaction with LocalStack.
 *
 * @author Waldemar Hummer
 */
@RunWith(LocalstackOutsideDockerTestRunner.class)
@ExtendWith(LocalstackExtension.class)
@org.junit.Ignore
@org.junit.jupiter.api.Disabled
// [JUnit5] Revert environment variables to the back after the test suite (class)
@EnvironmentVariableSandbox
public class BasicFunctionalityTest {

    static {
        /* Disable SSL certificate checks for local testing */
        if (Localstack.useSSL()) {
            CommonUtils.disableSslCertChecking();
        }
    }

    // [JUnit4] Revert environment variables to the back after the test suite (class)
    @ClassRule
    public static EnvironmentVariableSandboxRule environmentVariableSandboxRule = new EnvironmentVariableSandboxRule();

    @org.junit.BeforeClass
    @org.junit.jupiter.api.BeforeAll
    public static void beforeAll() {
        CommonUtils.setEnv("AWS_CBOR_DISABLE", "1");
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testDevEnvironmentSetup() {
        Assertions.assertThat(Localstack.isDevEnvironment()).isTrue();
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testLocalKinesisAPI() throws InterruptedException {
        AmazonKinesis kinesis = TestUtils.getClientKinesis();
        ListStreamsResult streams = kinesis.listStreams();
        Assertions.assertThat(streams.getStreamNames()).isNotNull();
        String streamName = UUID.randomUUID().toString();
        kinesis.createStream(streamName, 1);
        // sleep required because of kinesalite
        Thread.sleep(500);
        // put record to stream
        PutRecordRequest req = new PutRecordRequest();
        req.setPartitionKey("foobar-key");
        req.setData(ByteBuffer.wrap("{}".getBytes()));
        req.setStreamName(streamName);
        kinesis.putRecord(req);
        final ByteBuffer data = ByteBuffer.wrap("{\"test\":\"test\"}".getBytes());
        kinesis.putRecord(streamName, data, "partition-key");
        // clean up
        kinesis.deleteStream(streamName);
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testKinesisLambdaIntegration() throws Exception {
        AmazonKinesis kinesis = TestUtils.getClientKinesis();
        AWSLambda lambda = TestUtils.getClientLambda();
        String functionName = UUID.randomUUID().toString();
        String streamName = UUID.randomUUID().toString();

        // create function
        CreateFunctionRequest request = new CreateFunctionRequest();
        request.setFunctionName(functionName);
        request.setRuntime(Runtime.Java8);
        request.setCode(LocalTestUtilSDKV1.createFunctionCode(KinesisLambdaHandler.class));
        request.setHandler(KinesisLambdaHandler.class.getName());
        request.setRole("r1");
        lambda.createFunction(request);

        // create stream
        kinesis.createStream(streamName, 1);
        Thread.sleep(500);
        String streamArn = kinesis.describeStream(streamName).getStreamDescription().getStreamARN();

        // create mapping
        CreateEventSourceMappingRequest mapping = new CreateEventSourceMappingRequest();
        mapping.setFunctionName(functionName);
        mapping.setEventSourceArn(streamArn);
        mapping.setStartingPosition("LATEST");
        lambda.createEventSourceMapping(mapping);

        // push event
        kinesis.putRecord(streamName, ByteBuffer.wrap("{\"foo\": \"bar\"}".getBytes()), "partitionKey1");
        // TODO: have Lambda store the record to S3, retrieve it from there, compare result
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testSQSLambdaIntegration() throws Exception {
        AmazonSQS clientSQS = TestUtils.getClientSQS();
        AWSLambda lambda = TestUtils.getClientLambda();
        AmazonS3 s3 = TestUtils.getClientS3();
        String functionName = UUID.randomUUID().toString();
        String sqsQueueName = UUID.randomUUID().toString();

        // create function
        CreateFunctionRequest request = new CreateFunctionRequest();
        request.setFunctionName(functionName);
        request.setRuntime(Runtime.Java8);
        request.setRole("r1");
        request.setCode(LocalTestUtilSDKV1.createFunctionCode(SQSLambdaHandler.class));
        request.setHandler(SQSLambdaHandler.class.getName());
        lambda.createFunction(request);

        // create stream
        CreateQueueResult queue = clientSQS.createQueue(sqsQueueName);
        Thread.sleep(500);
        GetQueueAttributesResult queueAttributes = clientSQS.getQueueAttributes(new GetQueueAttributesRequest()
                .withQueueUrl(queue.getQueueUrl())
                .withAttributeNames(QueueAttributeName.QueueArn));
        String queueArn = queueAttributes.getAttributes().get(QueueAttributeName.QueueArn.name());

        // create mapping
        CreateEventSourceMappingRequest mapping = new CreateEventSourceMappingRequest();
        mapping.setFunctionName(functionName);
        mapping.setEventSourceArn(queueArn);
        lambda.createEventSourceMapping(mapping);

        // create a s3 bucket
        String testBucket = UUID.randomUUID().toString();
        s3.createBucket(testBucket);

        // push event
        clientSQS.sendMessage(queue.getQueueUrl(), testBucket);

        Runnable check = new Runnable() {
            public void run() {
                // Assert that file has been written by Lambda
                ObjectListing objectListing = s3.listObjects(testBucket);
                Assertions.assertThat(objectListing.getObjectSummaries()).hasSize(1);
                String key = objectListing.getObjectSummaries().get(0).getKey();
                Assertions.assertThat(key).startsWith(SQSLambdaHandler.fileName[0]);
                Assertions.assertThat(key).endsWith(SQSLambdaHandler.fileName[1]);
                String message = s3.getObjectAsString(testBucket, key);
                Assertions.assertThat(message).isEqualTo(SQSLambdaHandler.DID_YOU_GET_THE_MESSAGE);
            }
        };

        LocalTestUtil.retry(check, 5, 1);
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testSQSQueueAttributes() {
        // Based on https://github.com/localstack/localstack/issues/1551

        AmazonSQSAsync sqsAsync = TestUtils.getClientSQSAsync();

        CreateQueueResult result1 = sqsAsync.createQueue("1551-test");
        CreateQueueResult result2 = sqsAsync.createQueue("1551-test-dlq");

        final String queueArn = "QueueArn";
        GetQueueAttributesResult dlqQueueAttributes = sqsAsync.getQueueAttributes(result2.getQueueUrl(),
                Collections.singletonList(queueArn));
        dlqQueueAttributes.getAttributes().get(queueArn);

        // set queue attributes
        final Map<String, String> attributes = new HashMap<>();
        attributes.put("VisibilityTimeout", "60");
        attributes.put("MessageRetentionPeriod", "345600");
        attributes.put("RedrivePolicy", "{\"foo\":1}");
        final String queueUrl = result1.getQueueUrl();
        sqsAsync.setQueueAttributes(queueUrl, attributes);

        // get and assert queue attributes
        Map<String, String> result = sqsAsync.getQueueAttributes(queueUrl, Arrays.asList("All")).getAttributes();
        Assert.assertEquals(result.get("MessageRetentionPeriod"), "345600");
        Assert.assertEquals(result.get("VisibilityTimeout"), "60");
        Assert.assertEquals(result.get("RedrivePolicy"), "{\"foo\":1}");
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testLocalS3API() throws Exception {
        AmazonS3 s3 = TestUtils.getClientS3();
        List<Bucket> buckets = s3.listBuckets();
        Assertions.assertThat(buckets).isNotNull();

        // run S3 sample
        String s3Endpoint = Localstack.INSTANCE.getEndpointS3();
        S3Sample.runTest(TEST_CREDENTIALS, s3Endpoint);

        // run example with ZIP file upload
        String testBucket = UUID.randomUUID().toString();
        s3.createBucket(testBucket);
        File file = Files.createTempFile("localstack", "s3").toFile();
        file.deleteOnExit();
        ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(file));
        zipOutputStream.putNextEntry(new ZipEntry("Some content"));
        zipOutputStream.write("Some text content".getBytes());
        zipOutputStream.closeEntry();
        zipOutputStream.close();
        s3.putObject(testBucket, file.getName(), file);
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testLocalLambdaAPI() {
        AWSLambda lambda = TestUtils.getClientLambda();
        ListFunctionsResult functions = lambda.listFunctions();
        Assertions.assertThat(functions.getFunctions()).isNotNull();
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testLocalSQSAPI() {
        AmazonSQS sqs = TestUtils.getClientSQS();
        ListQueuesResult queues = sqs.listQueues();
        Assertions.assertThat(queues.getQueueUrls()).isNotNull();

        for (String queueName : Arrays.asList("java_test_queue", "java_test_queue.fifo")) {
            // create queue
            CreateQueueRequest createQueueRequest = new CreateQueueRequest();
            createQueueRequest.setQueueName(queueName);
            CreateQueueResult newQueue = sqs.createQueue(createQueueRequest);
            String queueUrl = newQueue.getQueueUrl();

            // send message
            SendMessageRequest send = new SendMessageRequest(queueUrl, "body");
            SendMessageResult sendResult = sqs.sendMessage(send);
            Assertions.assertThat(sendResult.getMD5OfMessageBody()).isNotNull();

            // receive message
            ReceiveMessageRequest request = new ReceiveMessageRequest(queueUrl);
            request.setWaitTimeSeconds(1);
            request.setMaxNumberOfMessages(1);
            request.setMessageAttributeNames(Arrays.asList("All"));
            request.setAttributeNames(Arrays.asList("All"));
            ReceiveMessageResult result = sqs.receiveMessage(request);
            Assertions.assertThat(result.getMessages()).isNotNull().hasSize(1);

            // delete queue
            DeleteQueueRequest deleteQueue = new DeleteQueueRequest();
            deleteQueue.setQueueUrl(queueUrl);
            sqs.deleteQueue(deleteQueue);
        }
    }

}
