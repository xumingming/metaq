package com.taobao.metamorphosis.tools.monitor.core;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import com.taobao.metamorphosis.Message;
import com.taobao.metamorphosis.client.MessageSessionFactory;
import com.taobao.metamorphosis.client.MetaClientConfig;
import com.taobao.metamorphosis.client.MetaMessageSessionFactory;
import com.taobao.metamorphosis.client.consumer.ConsumerConfig;
import com.taobao.metamorphosis.client.consumer.MessageConsumer;
import com.taobao.metamorphosis.client.consumer.MessageIterator;
import com.taobao.metamorphosis.cluster.Partition;
import com.taobao.metamorphosis.exception.InvalidMessageException;
import com.taobao.metamorphosis.exception.MetaClientException;


/**
 * ����ֱ��һ̨broker����Ϣ������
 * 
 * @author �޻�
 * @since 2011-5-24 ����11:35:48
 */
// ����ά��offset,���̰߳�ȫ
public class MsgReceiver {

    /** һ̨broker�ϲ�ͬ�����ϵĽ���offset */
    private final Map<String/* partition */, Long/* offset */> offsetMap = new HashMap<String, Long>();

    final private MessageConsumer consumer;

    final static private String group = "meta-monitor-receive";

    private String serverUrl = StringUtils.EMPTY;
    private final MessageSessionFactory sessionFactory;


    public MsgReceiver(String serverUrl, MonitorConfig monitorConfig) throws MetaClientException {
        this.serverUrl = serverUrl;
        MetaClientConfig metaClientConfig = monitorConfig.metaClientConfigOf(serverUrl);
        this.sessionFactory = new MetaMessageSessionFactory(metaClientConfig);
        this.consumer = this.sessionFactory.createConsumer(new ConsumerConfig(group));
    }


    /**
     * <pre>
     * ͬ���ķ�ʽ������Ϣ.
     * ������partitionβ����ʼ����(��Ҫ�ѽ��յ�offset����Ϊ��һ�γɹ�������Ϣ���offset)
     * �쳣����:���ﲶ�������쳣(Error����),װ��result����ʽ����
     * </pre>
     */
    public ReveiceResult get(String topic, Partition partition) {
        long offset = this.getOffset(partition);
        ReveiceResult result = new ReveiceResult(topic, partition, offset, this.serverUrl);
        MessageIterator it = null;
        try {
            int i = 0;
            while ((it = this.consumer.get(topic, partition, offset, 1024 * 1024)) != null) {
                // ��ֹ��һ�δ�0-offset����̫�������.
                if (i++ >= 3) {
                    break;
                }
                while (it.hasNext()) {
                    final Message msg = it.next();
                    result.addMessage(msg);
                    // System.out.println("Receive message " + new
                    // String(msg.getData()));
                }
                offset += it.getOffset();
                this.setOffset(partition, offset);
            }
        }
        catch (InvalidMessageException e) {
            result.setException(e);
        }
        catch (MetaClientException e) {
            result.setException(e);
        }
        catch (InterruptedException e) {
            result.setException(e);
            Thread.currentThread().interrupt();
        }
        catch (Exception e) {
            result.setException(e);
        }
        return result;
    }


    public void setOffset(Partition partition, long offset) {
        this.offsetMap.put(partition.toString(), offset);
    }


    public long getOffset(Partition partition) {
        String key = partition.toString();
        Long offset = this.offsetMap.get(key);
        if (offset == null) {
            this.offsetMap.put(key, Long.valueOf(0));
        }
        return this.offsetMap.get(key).longValue();
    }


    public void dispose() {
        try {
            this.sessionFactory.shutdown();
        }
        catch (MetaClientException e) {
            // ignore
        }
    }

}