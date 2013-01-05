package com.taobao.metamorphosis.client.consumer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.taobao.gecko.core.command.ResponseCommand;
import com.taobao.gecko.core.util.OpaqueGenerator;
import com.taobao.gecko.service.exception.NotifyRemotingException;
import com.taobao.metamorphosis.Message;
import com.taobao.metamorphosis.client.MetaMessageSessionFactory;
import com.taobao.metamorphosis.client.RemotingClientWrapper;
import com.taobao.metamorphosis.client.consumer.SimpleFetchManager.FetchRequestRunner;
import com.taobao.metamorphosis.client.consumer.storage.OffsetStorage;
import com.taobao.metamorphosis.client.extension.FormatCheck;
import com.taobao.metamorphosis.client.producer.ProducerZooKeeper;
import com.taobao.metamorphosis.cluster.Broker;
import com.taobao.metamorphosis.cluster.Partition;
import com.taobao.metamorphosis.exception.MetaClientException;
import com.taobao.metamorphosis.exception.MetaOpeartionTimeoutException;
import com.taobao.metamorphosis.network.BooleanCommand;
import com.taobao.metamorphosis.network.DataCommand;
import com.taobao.metamorphosis.network.FetchCommand;
import com.taobao.metamorphosis.network.GetCommand;
import com.taobao.metamorphosis.network.HttpStatus;
import com.taobao.metamorphosis.network.MessageTypeCommand;
import com.taobao.metamorphosis.network.OffsetCommand;
import com.taobao.metamorphosis.utils.MetaStatLog;
import com.taobao.metamorphosis.utils.StatConstants;
import com.taobao.metaq.commons.MetaMessageDecoder;
import com.taobao.metaq.commons.MetaMessageWrapper;


/**
 * ��Ϣ�����߻���
 * 
 * @author boyan
 * @Date 2011-4-23
 * @author wuhua
 * @Date 2011-6-26
 * 
 */
public class SimpleMessageConsumer implements MessageConsumer, InnerConsumer {
    private static final int DEFAULT_OP_TIMEOUT = 10000;

    static final Log log = LogFactory.getLog(FetchRequestRunner.class);

    private final RemotingClientWrapper remotingClient;

    private final ConsumerConfig consumerConfig;

    private final ConsumerZooKeeper consumerZooKeeper;

    private final MetaMessageSessionFactory messageSessionFactory;

    private final OffsetStorage offsetStorage;

    private final LoadBalanceStrategy loadBalanceStrategy;

    private final ProducerZooKeeper producerZooKeeper;

    private final ScheduledExecutorService scheduledExecutorService;

    private final SubscribeInfoManager subscribeInfoManager;

    private final RecoverManager recoverStorageManager;

    private final ConcurrentHashMap<String/* topic */, SubscriberInfo> topicSubcriberRegistry =
            new ConcurrentHashMap<String, SubscriberInfo>();

    private FetchManager fetchManager;
    private boolean server14 = true;


    public SimpleMessageConsumer(final MetaMessageSessionFactory messageSessionFactory,
            final RemotingClientWrapper remotingClient, final ConsumerConfig consumerConfig,
            final ConsumerZooKeeper consumerZooKeeper, final ProducerZooKeeper producerZooKeeper,
            final SubscribeInfoManager subscribeInfoManager, final RecoverManager recoverManager,
            final OffsetStorage offsetStorage, final LoadBalanceStrategy loadBalanceStrategy) {
        super();
        this.messageSessionFactory = messageSessionFactory;
        this.remotingClient = remotingClient;
        this.consumerConfig = consumerConfig;
        this.producerZooKeeper = producerZooKeeper;
        this.consumerZooKeeper = consumerZooKeeper;
        this.offsetStorage = offsetStorage;
        this.subscribeInfoManager = subscribeInfoManager;
        this.recoverStorageManager = recoverManager;
        this.fetchManager = new SimpleFetchManager(consumerConfig, this);
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        this.loadBalanceStrategy = loadBalanceStrategy;
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                SimpleMessageConsumer.this.consumerZooKeeper
                    .commitOffsets(SimpleMessageConsumer.this.fetchManager);
            }
        }, consumerConfig.getCommitOffsetPeriodInMills(), consumerConfig.getCommitOffsetPeriodInMills(),
            TimeUnit.MILLISECONDS);
    }


    FetchManager getFetchManager() {
        return this.fetchManager;
    }


    void setFetchManager(final FetchManager fetchManager) {
        this.fetchManager = fetchManager;
    }


    ConcurrentHashMap<String, SubscriberInfo> getTopicSubcriberRegistry() {
        return this.topicSubcriberRegistry;
    }


    @Override
    public OffsetStorage getOffsetStorage() {
        return this.offsetStorage;
    }


    @Override
    public synchronized void shutdown() throws MetaClientException {
        if (this.fetchManager.isShutdown()) {
            return;
        }
        try {
            this.fetchManager.stopFetchRunner();
            this.consumerZooKeeper.unRegisterConsumer(this.fetchManager);
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        finally {
            this.scheduledExecutorService.shutdownNow();
            this.offsetStorage.close();
            // ɾ������Ķ��Ĺ�ϵ
            this.subscribeInfoManager.removeGroup(this.consumerConfig.getGroup());
            this.messageSessionFactory.removeChild(this);
        }

    }


    @Override
    public MessageConsumer subscribe(final String topic, final int maxSize, final MessageListener messageListener)
            throws MetaClientException {
        return subscribe(topic, maxSize, messageListener, null);
    }
    
    @Override
    public MessageConsumer subscribe(String topic, int maxSize, MessageListener messageListener, String[] messageTypes)
    		throws MetaClientException {
    	this.checkState();
        if (StringUtils.isBlank(topic)) {
            throw new IllegalArgumentException("Blank topic");
        }
        FormatCheck.checkTopicFormat(topic);
        this.checkType(messageTypes);
        
        if (messageListener == null) {
            throw new IllegalArgumentException("Null messageListener");
        }
        // �����ӵ�����������
        this.subscribeInfoManager.subscribe(topic, this.consumerConfig.getGroup(), maxSize, messageListener);
        // Ȼ�����ӵ������Ĺ�����
        SubscriberInfo info = this.topicSubcriberRegistry.get(topic);
        if (info == null) {
            info = new SubscriberInfo(messageListener, maxSize, messageTypes);
            final SubscriberInfo oldInfo = this.topicSubcriberRegistry.putIfAbsent(topic, info);
            if (oldInfo != null) {
                throw new MetaClientException("Topic=" + topic + " has been subscribered");
            }
            return this;
        }
        else {
            throw new MetaClientException("Topic=" + topic + " has been subscribered");
        }
    }
    
    private boolean checkType(String[] types){
    	if(types == null){
    		return true;
    	}
    	for(String t : types){
    		if(StringUtils.isBlank(t)){
    			throw new IllegalArgumentException("type list contains blank string");
    		}
    	}
    	return true;
    }


    @Override
    public void appendCouldNotProcessMessage(final Message message) throws IOException {
        // Ŀǰ�Ĵ����ǽ������ش洢����������
        log.warn("Message could not process,save to local.MessageId=" + message.getId() + ",Topic="
                + message.getTopic() + ",Partition=" + message.getPartition());
        this.recoverStorageManager.append(this.consumerConfig.getGroup(), message);
    }


    private void checkState() {
        if (this.fetchManager.isShutdown()) {
            throw new IllegalStateException("Consumer has been shutdown");
        }
    }


    @Override
    public void completeSubscribe() throws MetaClientException {
        this.checkState();
        try {
            this.consumerZooKeeper.registerConsumer(this.consumerConfig, this.fetchManager,
                this.topicSubcriberRegistry, this.offsetStorage, this.loadBalanceStrategy);
        }
        catch (final Exception e) {
            throw new MetaClientException("ע�ᶩ����ʧ��", e);
        }
    }


    @Override
    public MessageListener getMessageListener(final String topic) {
        final SubscriberInfo info = this.topicSubcriberRegistry.get(topic);
        if (info == null) {
            return null;
        }
        return info.getMessageListener();
    }


    @Override
    public long offset(final FetchRequest fetchRequest) throws MetaClientException {
        final long start = System.currentTimeMillis();
        boolean success = false;
        try {
            final long currentOffset = fetchRequest.getOffset();
            final OffsetCommand offsetCmd =
                    new OffsetCommand(fetchRequest.getTopic(), this.consumerConfig.getGroup(),
                        fetchRequest.getPartition(), currentOffset, OpaqueGenerator.getNextOpaque());
            final String serverUrl = fetchRequest.getBroker().getZKString();
            final BooleanCommand booleanCmd =
                    (BooleanCommand) this.remotingClient.invokeToGroup(serverUrl, offsetCmd,
                        this.consumerConfig.getFetchTimeoutInMills(), TimeUnit.MILLISECONDS);
            switch (booleanCmd.getCode()) {
            case HttpStatus.Success:
                success = true;
                return Long.parseLong(booleanCmd.getErrorMsg());
            default:
                throw new MetaClientException(booleanCmd.getErrorMsg());
            }
        }
        catch (final MetaClientException e) {
            throw e;
        }
        catch (final TimeoutException e) {
            throw new MetaOpeartionTimeoutException("Send message timeout in "
                    + this.consumerConfig.getFetchTimeoutInMills() + " mills");
        }
        catch (final Exception e) {
            throw new MetaClientException("get offset failed,topic=" + fetchRequest.getTopic() + ",partition="
                    + fetchRequest.getPartition() + ",current offset=" + fetchRequest.getOffset(), e);
        }
        finally {
            final long duration = System.currentTimeMillis() - start;
            if (duration > 200) {
                MetaStatLog.addStatValue2(null, StatConstants.OFFSET_TIME_STAT, fetchRequest.getTopic(), duration);
            }
            if (!success) {
                MetaStatLog.addStat(null, StatConstants.OFFSET_FAILED_STAT, fetchRequest.getTopic());
            }
        }
    }


    @Override
    public MessageIterator fetch(final FetchRequest fetchRequest, long timeout, TimeUnit timeUnit)
            throws MetaClientException, InterruptedException {
        if (timeout <= 0 || timeUnit == null) {
            timeout = this.consumerConfig.getFetchTimeoutInMills();
            timeUnit = TimeUnit.MILLISECONDS;
        }
        final long start = System.currentTimeMillis();
        boolean success = false;
        try {
            final long currentOffset = fetchRequest.getOffset();
            final GetCommand getCmd =
                    new GetCommand(fetchRequest.getTopic(), this.consumerConfig.getGroup(),
                        fetchRequest.getPartition(), currentOffset, fetchRequest.getMaxSize(),
                        OpaqueGenerator.getNextOpaque());
            final String serverUrl = fetchRequest.getBroker().getZKString();
            final ResponseCommand response =
                    this.remotingClient.invokeToGroup(serverUrl, getCmd, timeout, timeUnit);
            if (response instanceof DataCommand) {
                final DataCommand dataCmd = (DataCommand) response;
                final byte[] data = dataCmd.getData();
                // ��ȡ���������ز����ʱ������maxSize
                if (data.length < fetchRequest.getMaxSize() / 2) {
                    fetchRequest.decreaseMaxSize();
                }
                success = true;
                return new MessageIterator(fetchRequest.getTopic(), data);
            }
            else {
                final BooleanCommand booleanCmd = (BooleanCommand) response;
                switch (booleanCmd.getCode()) {
                case HttpStatus.NotFound:
                    success = true;
                    return null;
                case HttpStatus.Forbidden:
                    success = true;
                    return null;
                case HttpStatus.Moved:
                    success = true;
                    fetchRequest.resetRetries();
                    fetchRequest.setOffset(Long.parseLong(booleanCmd.getErrorMsg()), -1, true);
                    return null;
                default:
                    throw new MetaClientException(((BooleanCommand) response).getErrorMsg());
                }
            }

        }
        catch (final TimeoutException e) {
            throw new MetaOpeartionTimeoutException("Send message timeout in "
                    + this.consumerConfig.getFetchTimeoutInMills() + " mills");
        }
        catch (final MetaClientException e) {
            throw e;
        }
        catch (final InterruptedException e) {
            throw e;
        }
        catch (final Exception e) {
            throw new MetaClientException("get message failed,topic=" + fetchRequest.getTopic() + ",partition="
                    + fetchRequest.getPartition() + ",offset=" + fetchRequest.getOffset(), e);
        }
        finally {
            final long duration = System.currentTimeMillis() - start;
            if (duration > 200) {
                MetaStatLog.addStatValue2(null, StatConstants.GET_TIME_STAT, fetchRequest.getTopic(), duration);
            }
            if (!success) {
                MetaStatLog.addStat(null, StatConstants.GET_FAILED_STAT, fetchRequest.getTopic());
            }
        }
    }


    @Override
    public void setSubscriptions(final Collection<Subscription> subscriptions) throws MetaClientException {
        if (subscriptions == null) {
            return;
        }
        for (final Subscription subscription : subscriptions) {
            this.subscribe(subscription.getTopic(), subscription.getMaxSize(), subscription.getMessageListener());
        }
    }


    @Override
    public MessageIterator get(final String topic, final Partition partition, final long offset,
            final int maxSize, final long timeout, final TimeUnit timeUnit) throws MetaClientException,
            InterruptedException {
        this.producerZooKeeper.publishTopic(topic);
        final Broker broker =
                new Broker(partition.getBrokerId(), this.producerZooKeeper.selectBroker(topic, partition));
        final TopicPartitionRegInfo topicPartitionRegInfo = new TopicPartitionRegInfo(topic, partition, offset);
        return this.fetch(new FetchRequest(broker, 0, topicPartitionRegInfo, maxSize), timeout, timeUnit);
    }


    @Override
    public ConsumerConfig getConsumerConfig() {
        return this.consumerConfig;
    }


    @Override
    public MessageIterator get(final String topic, final Partition partition, final long offset, final int maxSize)
            throws MetaClientException, InterruptedException {
        return this.get(topic, partition, offset, maxSize, DEFAULT_OP_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    @Override
    public FetchResult fetchAll(final FetchRequest fetchRequest, long timeout, TimeUnit timeUnit)
            throws MetaClientException, InterruptedException {
        if (timeout <= 0 || timeUnit == null) {
            timeout = this.consumerConfig.getFetchTimeoutInMills();
            timeUnit = TimeUnit.MILLISECONDS;
        }
        final long start = System.currentTimeMillis();
        boolean success = false;
        final long currentOffset = fetchRequest.getOffset();
        try {
        	GetCommand getCmd = null;
        	SubscriberInfo subInfo = this.topicSubcriberRegistry.get(fetchRequest.getTopic());
        	Set<String> messageTypeList = null;
        	if(subInfo != null){
        		messageTypeList = subInfo.getMessageTypes();
        	}
        	if(this.consumerConfig.isVersion2() && messageTypeList != null){//�û�ʹ���°汾�Ľӿڲ�ʹ���µ�Э��
        		getCmd =
                        new FetchCommand(this.consumerConfig.getVersion(), fetchRequest.getTopic(), this.consumerConfig.getGroup(),
                            fetchRequest.getPartition(), currentOffset, fetchRequest.getMaxSize(),
                            OpaqueGenerator.getNextOpaque(), MetaMessageSessionFactory.startTime);
        	} else {
        		getCmd =
                        new GetCommand(fetchRequest.getTopic(), this.consumerConfig.getGroup(),
                            fetchRequest.getPartition(), currentOffset, fetchRequest.getMaxSize(),
                            OpaqueGenerator.getNextOpaque());
        	}
        	
            final String serverUrl = fetchRequest.getBroker().getZKString();
            final ResponseCommand response =
                    this.remotingClient.invokeToGroup(serverUrl, getCmd, timeout, timeUnit);
            if (response instanceof DataCommand) {
                final DataCommand dataCmd = (DataCommand) response;
                final byte[] data = dataCmd.getData();
                if (data.length < (MetaMessageDecoder.MessageFlagPostion + 4)) {
                    log.fatal("fetch a invalid message " + data.length);
                    return null;
                }

                // ʶ��������汾
                java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.wrap(data);
                int messageFlag = byteBuffer.getInt(MetaMessageDecoder.MessageFlagPostion);
                // 2.0�汾
                if ((messageFlag & MetaMessageDecoder.NewServerFlag) == MetaMessageDecoder.NewServerFlag) {
                	server14 = false;
                    success = true;
                    List<Message> msgList = new ArrayList<Message>(100);
                    List<MetaMessageWrapper> wrapperList = MetaMessageDecoder.decodes(byteBuffer);
                    if (!wrapperList.isEmpty()) {
                        for (MetaMessageWrapper wrapper : wrapperList) {
                        	String type = wrapper.getMetaMessage().getType();
                        	if(messageTypeList != null && !messageTypeList.contains("*") && !messageTypeList.contains(type)){
                        		continue;
                        	}
                            Message msg =
                                    new Message(fetchRequest.getTopic(), wrapper.getMetaMessage().getBody(),
                                        wrapper.getMetaMessage().getAttribute());
                            msg.setOffset(wrapper.getMetaMessageAnnotation().getQueueOffset());
                            msg.setId(wrapper.getMetaMessageAnnotation().getPhysicOffset());
                            msg.setMsgNewId(wrapper.getMetaMessageAnnotation().getMsgId());
                            msgList.add(msg);
                        }
                    }

                    if (msgList.isEmpty()){
                    	if(wrapperList != null && !wrapperList.isEmpty()){
                    		MetaMessageWrapper wrapper = wrapperList.get(wrapperList.size() - 1);
                    		fetchRequest.setOffset(wrapper.getMetaMessageAnnotation().getQueueOffset() + 1, 
                    				wrapper.getMetaMessageAnnotation().getPhysicOffset(), true);
                    	}
                    	return null;
                    }
                    return new FetchResult(true, msgList, null);
                }
                // 1.4�汾
                else {
                	server14 = true;
                    // ��ȡ���������ز����ʱ������maxSize
                    if (data.length < fetchRequest.getMaxSize() / 2) {
                        fetchRequest.decreaseMaxSize();
                    }
                    success = true;
                    return new FetchResult(false, null, new MessageIterator(fetchRequest.getTopic(), data));
                }
            }
            else {
                final BooleanCommand booleanCmd = (BooleanCommand) response;
                switch (booleanCmd.getCode()) {
                case HttpStatus.NotFound:
                    success = true;
                    if (log.isDebugEnabled()) {
                        log.debug(booleanCmd.getErrorMsg());
                    }
                    return null;
                case HttpStatus.Forbidden:
                    success = true;
                    return null;
                case HttpStatus.Moved:
                    success = true;
                    fetchRequest.resetRetries();
                    long serverPushedOffset = Long.parseLong(booleanCmd.getErrorMsg());
                    fetchRequest.setOffset(serverPushedOffset, 100, true);
                    if(!server14){
                    	log.warn("consumer request offset: " + currentOffset
                                + " invalid or not matched, server pushed new offset: " + serverPushedOffset);
                    }
                    return null;
                case HttpStatus.Continue:
                	success = true;
                	SubscriberInfo info = registeMessageType(fetchRequest);
                	if(info == null){
                		log.error("consumer report message types failed.");
                	} else {
                		log.info("consumer report message types success : " + info.getMessageTypes().toString());
                	}
                	return null;
                default:
                    throw new MetaClientException(((BooleanCommand) response).getErrorMsg());
                }
            }

        }
        catch (final TimeoutException e) {
            throw new MetaOpeartionTimeoutException("pull message timeout in "
                    + this.consumerConfig.getFetchTimeoutInMills() + " mills, requestOffset " + currentOffset);
        }
        catch (final MetaClientException e) {
            throw e;
        }
        catch (final InterruptedException e) {
            throw e;
        }
        catch (final Exception e) {
            throw new MetaClientException("get message failed,topic=" + fetchRequest.getTopic() + ",partition="
                    + fetchRequest.getPartition() + ",offset=" + fetchRequest.getOffset(), e);
        }
        finally {
            final long duration = System.currentTimeMillis() - start;
            if (duration > 200) {
                MetaStatLog.addStatValue2(null, StatConstants.GET_TIME_STAT, fetchRequest.getTopic(), duration);
            }
            if (!success) {
                MetaStatLog.addStat(null, StatConstants.GET_FAILED_STAT, fetchRequest.getTopic());
            }
        }
    }
    
    
    private SubscriberInfo registeMessageType(FetchRequest req){
    	SubscriberInfo info = this.topicSubcriberRegistry.get(req.getTopic());
    	if(info == null){
    		log.warn("query topic's["+req.getTopic()+"] subscriberInfo is null.");
    		return null;
    	}
    	Set<String> messageTypeList = info.getMessageTypes();
    	MessageTypeCommand mtCmd = new MessageTypeCommand(this.consumerConfig.getVersion(), this.consumerConfig.getGroup(),
    			req.getTopic(), OpaqueGenerator.getNextOpaque(), messageTypeList, MetaMessageSessionFactory.startTime);
    	try {
			ResponseCommand response = this.remotingClient.invokeToGroup(req.getBroker().getZKString(), mtCmd, 
					this.consumerConfig.getFetchTimeoutInMills(), TimeUnit.MILLISECONDS);
			if(response instanceof BooleanCommand){
				BooleanCommand bc = (BooleanCommand)response;
				if(bc.getCode() == HttpStatus.Success){
					return info;
				} else {
					return null;
				}
			}
		} catch (InterruptedException e) {
			log.error("registe message type interrupted,"+e.getMessage(), e.getCause());
		} catch (TimeoutException e) {
			log.error("registe message type timeout,"+e.getMessage(), e.getCause());
		} catch (NotifyRemotingException e) {
			log.error("registe message type failed, "+e.getMessage(), e.getCause());
		}
    	return null;
    }

    @Override
    public DequeueResult dequeue(String topic, Partition partition, long offset, int maxSize)
            throws MetaClientException, InterruptedException {
        return this.dequeue(topic, partition, offset, maxSize, DEFAULT_OP_TIMEOUT, TimeUnit.MILLISECONDS);
    }


    @Override
    public DequeueResult dequeue(String topic, Partition partition, long offset, int maxSize, long timeout,
            TimeUnit timeUnit) throws MetaClientException, InterruptedException {
        this.producerZooKeeper.publishTopic(topic);
        String brokerUrl = this.producerZooKeeper.selectBroker(topic, partition);
        if (brokerUrl != null) {
            final Broker broker = new Broker(partition.getBrokerId(), brokerUrl);
            final TopicPartitionRegInfo topicPartitionRegInfo =
                    new TopicPartitionRegInfo(topic, partition, offset);

            return this.fetchSync(new FetchRequest(broker, 0, topicPartitionRegInfo, maxSize), timeout, timeUnit);
        }
        else {
            log.warn("the partiontion " +topic+" " + partition + " selectBroker failed, please retry.");
        }

        return new DequeueResult(DequeueStatus.STATUS_OTHER_ERROR, null, 0);
    }


    @Override
    public DequeueResult fetchSync(final FetchRequest fetchRequest, long timeout, TimeUnit timeUnit)
            throws MetaClientException, InterruptedException {
        if (timeout <= 0 || timeUnit == null) {
            timeout = this.consumerConfig.getFetchTimeoutInMills();
            timeUnit = TimeUnit.MILLISECONDS;
        }
        final long start = System.currentTimeMillis();
        boolean success = false;
        final long currentOffset = fetchRequest.getOffset();
        try {
            final GetCommand getCmd =
                    new GetCommand(fetchRequest.getTopic(), this.consumerConfig.getGroup(),
                        fetchRequest.getPartition(), currentOffset, fetchRequest.getMaxSize(),
                        OpaqueGenerator.getNextOpaque());
            final String serverUrl = fetchRequest.getBroker().getZKString();
            final ResponseCommand response =
                    this.remotingClient.invokeToGroup(serverUrl, getCmd, timeout, timeUnit);
            if (response instanceof DataCommand) {
                final DataCommand dataCmd = (DataCommand) response;
                final byte[] data = dataCmd.getData();
                if (data.length < (MetaMessageDecoder.MessageFlagPostion + 4)) {
                    log.fatal("fetch a invalid message " + data.length);
                    return new DequeueResult(DequeueStatus.STATUS_OTHER_ERROR, null, 0);
                }

                java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.wrap(data);
                int messageFlag = byteBuffer.getInt(MetaMessageDecoder.MessageFlagPostion);
                // 2.0
                if ((messageFlag & MetaMessageDecoder.NewServerFlag) == MetaMessageDecoder.NewServerFlag) {
                    success = true;
                    List<Message> msgList = new ArrayList<Message>(100);
                    List<MetaMessageWrapper> wrapperList = MetaMessageDecoder.decodes(byteBuffer);
                    if (!wrapperList.isEmpty()) {
                        for (MetaMessageWrapper wrapper : wrapperList) {
                            Message msg =
                                    new Message(fetchRequest.getTopic(), wrapper.getMetaMessage().getBody(),
                                        wrapper.getMetaMessage().getAttribute());
                            msg.setOffset(wrapper.getMetaMessageAnnotation().getQueueOffset());
                            msg.setId(wrapper.getMetaMessageAnnotation().getPhysicOffset());
                            msg.setMsgNewId(wrapper.getMetaMessageAnnotation().getMsgId());
                            msgList.add(msg);
                        }
                    }

                    if (msgList.isEmpty()) {
                        log.error("fetch sync OK, but no message");
                        return new DequeueResult(DequeueStatus.STATUS_OTHER_ERROR, null, 0);
                    }

                    return new DequeueResult(DequeueStatus.STATUS_OK, msgList, 0);
                }
                // 1.4
                else {
                    if (data.length < fetchRequest.getMaxSize() / 2) {
                        fetchRequest.decreaseMaxSize();
                    }
                    success = true;
                    log.info("server is not 2.0");
                    return new DequeueResult(DequeueStatus.STATUS_OTHER_ERROR, null, 0);
                }
            }
            else {
                final BooleanCommand booleanCmd = (BooleanCommand) response;
                switch (booleanCmd.getCode()) {
                case HttpStatus.NotFound:
                    success = true;
                    if (log.isDebugEnabled()) {
                        log.debug(booleanCmd.getErrorMsg());
                    }
                    return new DequeueResult(DequeueStatus.STATUS_NOT_FOUND, null, 0);
                case HttpStatus.Forbidden:
                    success = true;
                    return new DequeueResult(DequeueStatus.STATUS_OTHER_ERROR, null, 0);
                case HttpStatus.Moved:
                    success = true;
                    fetchRequest.resetRetries();
                    long serverPushedOffset = Long.parseLong(booleanCmd.getErrorMsg());
                    fetchRequest.setOffset(serverPushedOffset, 100, true);
                    log.warn("consumer request offset: " + currentOffset
                            + " invalid or not matched, server pushed new offset: " + serverPushedOffset);
                    return new DequeueResult(DequeueStatus.STATUS_MOVED, null, serverPushedOffset);
                default:
                    throw new MetaClientException(((BooleanCommand) response).getErrorMsg());
                }
            }

        }
        catch (final Exception e) {
            log.error(
                "fetchSync message failed,topic=" + fetchRequest.getTopic() + ",partition="
                        + fetchRequest.getPartition() + ",offset=" + fetchRequest.getOffset(), e);

            return new DequeueResult(DequeueStatus.STATUS_OTHER_ERROR, null, 0);
        }
    }
}