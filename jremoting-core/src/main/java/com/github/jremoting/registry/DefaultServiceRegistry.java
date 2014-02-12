package com.github.jremoting.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorListener;
import org.apache.curator.framework.api.UnhandledErrorListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher.Event.EventType;

import com.alibaba.fastjson.JSON;
import com.github.jremoting.core.ServiceParticipantInfo;
import com.github.jremoting.core.ServiceParticipantInfo.ParticipantType;
import com.github.jremoting.core.ServiceRegistry;
import com.github.jremoting.exception.RegistryExcpetion;
import com.github.jremoting.util.Logger;
import com.github.jremoting.util.LoggerFactory;

public class DefaultServiceRegistry implements ServiceRegistry,
												CuratorListener, 
												ConnectionStateListener,
												UnhandledErrorListener {

	private final Map<String, List<ServiceParticipantInfo>> cachedProviderInfos = new ConcurrentHashMap<String, List<ServiceParticipantInfo>>();
	private final Map<String, CountDownLatch> providerSubscribeLatch = new ConcurrentHashMap<String, CountDownLatch>();
	
	private final List<ServiceParticipantInfo> localParticipantInfos = new ArrayList<ServiceParticipantInfo>();
	private final CuratorFramework client; 
	private volatile boolean started = false;
	private volatile boolean closed = false;
	private final Logger logger = LoggerFactory.getLogger(DefaultServiceRegistry.class);
	
	public DefaultServiceRegistry(String zookeeperConnectionString) {
		RetryPolicy retryPolicy = new RetryNTimes(Integer.MAX_VALUE, 1000);
		this.client = CuratorFrameworkFactory.builder()
				.connectString(zookeeperConnectionString)
				.sessionTimeoutMs(10 * 1000).connectionTimeoutMs(5 * 1000)
				.namespace("jremoting").retryPolicy(retryPolicy).build();
	}
	
	
	@Override
	public List<ServiceParticipantInfo> getProviders(String serviceName) {
		
		List<ServiceParticipantInfo> providers = cachedProviderInfos.get(serviceName);
		if(providers != null) {
			return providers;
		}
		
		//if no provider then wait for first async subscribe action to complete and query again
		CountDownLatch subscribeLatch = providerSubscribeLatch.get(serviceName);
		if (subscribeLatch != null) {
			try {
				subscribeLatch.await();
			} catch (InterruptedException e) {
				throw new RegistryExcpetion(e.getMessage(), e);
			}
		}

		return cachedProviderInfos.get(serviceName);
	}
	
	@Override
	public void registerParticipant(ServiceParticipantInfo participantInfo) {
		if(participantInfo.getType() == ParticipantType.CONSUMER) {
			providerSubscribeLatch.put(participantInfo.getServiceName(), new CountDownLatch(1));
		}
		this.localParticipantInfos.add(participantInfo);
	}

	@Override
	public void start() {
		if(started) {
			return;
		}
		synchronized (this) {
			if(started) {
				return;
			}
			
			//close connection when process exit , let other consumers see this process die immediately
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				@Override
				public void run() {
					DefaultServiceRegistry.this.close();
				}
			}));
			
			this.client.getCuratorListenable().addListener(this);
			this.client.getConnectionStateListenable().addListener(this);
			this.client.getUnhandledErrorListenable().addListener(this);
			this.client.start();

			
			try {
				this.initLocalParticipantsServicePathInBackground();
				this.subscribeProviderInfosInBackground();
				this.publishParticipantInfosInBackground();
			} catch (Exception e) {
				throw new RegistryExcpetion(e.getMessage(), e);
			}
			
			this.started = true;
		}
		
	}
	
	private void subscribeProviderInfosInBackground() throws Exception   {
		for (ServiceParticipantInfo participantInfo : localParticipantInfos) {
			if(participantInfo.getType() == ParticipantType.CONSUMER) {
				String providersPath = String.format("/%s/providers", participantInfo.getServiceName());
				this.client.getChildren().watched().inBackground().forPath(providersPath);
			}
		}
	}
	
	private void getProviderInfosInBackground(String changedProvidersPath)
			throws Exception {
		this.client.getChildren().watched().inBackground().forPath(changedProvidersPath);
	}


	private void refreshProviders(String serviceName, List<String> providerJsons) {
		List<ServiceParticipantInfo> providers = new ArrayList<ServiceParticipantInfo>();
		for (String json : providerJsons) {
			providers.add(JSON.parseObject(json, ServiceParticipantInfo.class));
		}
		this.cachedProviderInfos.put(serviceName, providers);
		//if there are consumer threads blocked at get providers then wake up them
		if(this.providerSubscribeLatch.size() > 0) {
			CountDownLatch subscribeLatch = this.providerSubscribeLatch.remove(serviceName);
			if(subscribeLatch != null) {
				subscribeLatch.countDown();
			}
		}
	}
	
	private void initLocalParticipantsServicePathInBackground() throws Exception {
		for (ServiceParticipantInfo participantInfo: this.localParticipantInfos) {
			this.client.create().inBackground().forPath("/" + participantInfo.getServiceName());
			this.client.create().inBackground().forPath("/" + participantInfo.getServiceName() + "/providers");
			this.client.create().inBackground().forPath("/" + participantInfo.getServiceName() + "/consumers");
		}
	}
	
	private void publishParticipantInfosInBackground() throws Exception {
		for (ServiceParticipantInfo participantInfo: this.localParticipantInfos) {
			String  participantPath = null;
			if(participantInfo.getType() == ParticipantType.PROVIDER) {
				participantPath = String.format("/%s/providers/%s", 
						participantInfo.getServiceName(),JSON.toJSONString(participantInfo));
			}
			else {
				participantPath = String.format("/%s/consumers/%s", 
						participantInfo.getServiceName(),JSON.toJSONString(participantInfo));
			}
			
			this.client.create().withMode(CreateMode.EPHEMERAL).inBackground().forPath(participantPath);
		}
	}
	


	@Override
	public void close() {
		if (closed) {
			return;
		}

		synchronized (this) {
			if (closed) {
				return;
			}
			
			this.client.close();
			closed = true;
			logger.info("register closed before process exit!");
		}
	}


	@Override
	public void eventReceived(CuratorFramework client, CuratorEvent event)
			throws Exception {
		System.out.println(event);
		switch (event.getType()) {
		case WATCHED:
			if(event.getWatchedEvent().getType() == EventType.NodeChildrenChanged) {
				getProviderInfosInBackground(event.getPath());
			}
			break;
		case CHILDREN:
			if(event.getPath().endsWith("/providers")) {
				refreshProviders(parseServiveName(event.getPath()), event.getChildren());
			}
			break;
		default:
			break;
		}
	}
	
	private String parseServiveName(String path) {
		// path = /serviceName/providers
		return path.substring(1, path.indexOf("/providers"));
	}


	private ConnectionState currentState;

	@Override
	public void stateChanged(CuratorFramework client, ConnectionState newState) {
		switch (newState) {
		case CONNECTED:
			currentState = ConnectionState.CONNECTED;
		case LOST:
			currentState = ConnectionState.LOST;
			break;
		case RECONNECTED:
			if (currentState == ConnectionState.LOST) {
				try {
					this.subscribeProviderInfosInBackground();
					this.publishParticipantInfosInBackground();
				} catch (Exception e) {
					logger.error("republish local providers failed!", e);
				}
			}
			currentState = ConnectionState.RECONNECTED;
		default:
			break;
		}
	}

	@Override
	public void unhandledError(String message, Throwable e) {
		logger.error(message, e);
	}
}
