package com.github.jremoting.core;



public class Invoke extends Message {
	private final String version;
	private final String methodName;
	private final String interfaceName;
	private final Object[] args;
	private final Class<?>[] parameterTypes;
	private final String[] parameterTypeNames;
	

	private Object target;
	private ServiceRegistry registry;
	private final String serviceName;
	private final boolean generic;
	
	
	
	public Invoke(String interfaceName, String version,String methodName ,
			Serializer serializer, Object[] args, Class<?>[] parameterTypes) {
		super(true, serializer);
		this.args = args;
		this.interfaceName = interfaceName;
		this.version = version;
		this.methodName = methodName;
		this.parameterTypes = parameterTypes;
		
		this.parameterTypeNames = new String[this.parameterTypes.length];
		for (int i = 0; i < this.parameterTypeNames.length; i++) {
			this.parameterTypeNames[i] = this.parameterTypes[i].getName();
		}

		this.serviceName = this.interfaceName + ":" + this.version;
		this.generic = false;
	}
	
	public Invoke(String interfaceName, String version,String methodName ,
			Serializer serializer, Object[] args, String[] parameterTypeNames) {
		super(true, serializer);
		this.args = args;
		this.interfaceName = interfaceName;
		this.version = version;
		this.methodName = methodName;
		this.parameterTypeNames = parameterTypeNames;
		this.serviceName = this.interfaceName + ":" + this.version;
		this.generic = true;
		this.parameterTypes = null;
	}
	
	
	public Object[] getArgs() {
		return args;
	}


	public String getInterfaceName() {
		return interfaceName;
	}

	
	public String getVersion() {
		return version;
	}

	
	public String getMethodName() {
		return methodName;
	}
	
	public String getServiceName() {
		return serviceName;
	}

	public Object getTarget() {
		return target;
	}

	
	public void setTarget(Object target) {
		this.target = target;
	}

	public Class<?>[] getParameterTypes() {
		return parameterTypes;
	}


	public ServiceRegistry getRegistry() {
		return registry;
	}


	public void setRegistry(ServiceRegistry registry) {
		this.registry = registry;
	}

	public String[] getParameterTypeNames() {
		return parameterTypeNames;
	}
	public boolean isGeneric() {
		return generic;
	}
	
}
