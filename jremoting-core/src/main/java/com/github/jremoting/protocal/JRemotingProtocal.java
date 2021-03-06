package com.github.jremoting.protocal;

import java.util.HashMap;

import com.github.jremoting.core.HeartbeatMessage;
import com.github.jremoting.core.Invoke;
import com.github.jremoting.core.InvokeResult;
import com.github.jremoting.core.Message;
import com.github.jremoting.core.Protocal;
import com.github.jremoting.core.Serializer;
import com.github.jremoting.core.SerializerUtil;
import com.github.jremoting.core.ServiceRegistry;
import com.github.jremoting.exception.ProtocalException;
import com.github.jremoting.exception.ServerErrorException;
import com.github.jremoting.io.ByteBuffer;
import com.github.jremoting.io.ByteBufferInputStream;
import com.github.jremoting.io.ByteBufferOutputStream;
import com.github.jremoting.io.ObjectInput;
import com.github.jremoting.io.ObjectOutput;
import com.github.jremoting.util.Logger;
import com.github.jremoting.util.LoggerFactory;
import com.github.jremoting.util.ReflectionUtil;

public class JRemotingProtocal implements Protocal {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(JRemotingProtocal.class);
	
	public static final String NAME = "jremoting";
	
	public static final short MAGIC = (short) 0xBABE; //1011101010111110
	
	 // header length.
    protected static final int      HEAD_LENGTH      = 16;
    // message flag.
    protected static final int     FLAG_REQUEST       =  0x80; //10000000

    protected static final int     FLAG_TWOWAY        =  0x40; //01000000

    protected static final int     FLAG_EVENT     =  0x20;	  //00100000

    protected static final int      SERIALIZATION_MASK = 0x1f;		  //00011111
    
    
    protected static final int      STATUS_ERROR = 50;
    protected static final int      STATUS_OK = 20;
    
    private static final String NULL = "NULL";
    private final ServiceRegistry registry;
	private static Class<?>[] EMPTY_TYPE_ARRAY = new Class<?>[0];

	private final Serializer[] serializers;
	
	public JRemotingProtocal(Serializer[] serializers, ServiceRegistry registry) {
		this.serializers = SerializerUtil.reindex(serializers);
		this.registry = registry;
	}
	
	@Override
	public void encode(Message msg, ByteBuffer buffer) throws ProtocalException {
		
		try {
			boolean isHeartbeatMessage = msg instanceof HeartbeatMessage;
			boolean isTwoWay = msg.isTwoWay();
			boolean isRequest = msg instanceof Invoke;
			boolean isErrorMsg = (msg instanceof InvokeResult) && ((InvokeResult)msg).getResult() instanceof Throwable;
			int serializeId = isHeartbeatMessage ? 0 : msg.getSerializer().getId();
			
			int flag = (isRequest ? FLAG_REQUEST : 0)
					| (isTwoWay ? FLAG_TWOWAY : 0) 
					| (isHeartbeatMessage ? FLAG_EVENT : 0)
					| serializeId;
			
			int status = isErrorMsg ? STATUS_ERROR : STATUS_OK;
			
			//encode head
			buffer.writeShort(MAGIC);
			buffer.writeByte(flag);
			buffer.writeByte(status);
			buffer.writeLong(msg.getId());
			
			int bodyLengthOffset = buffer.writerIndex();
			buffer.writeInt(0);
			
			if(isHeartbeatMessage) {
				return;
			}
			
			Serializer serializer = serializers[msg.getSerializer().getId()];
			
			ObjectOutput output = serializer.createObjectOutput(new ByteBufferOutputStream(buffer));
			
			if(isErrorMsg) {
				InvokeResult errorResult = (InvokeResult)msg;
				Throwable error = (Throwable)errorResult.getResult();
				output.writeString(error.getMessage());
			}
			else if(isRequest) {
				Invoke invoke = (Invoke)msg;
				encodeRequestBody(invoke, output);
			}
			else {
				InvokeResult invokeResult = (InvokeResult)msg;
				if(invokeResult.getResult() == null) {
					output.writeString(NULL);
				}
				else {
					output.writeString(invokeResult.getResult().getClass().getName());
					output.writeObject(invokeResult.getResult());
				}
			}
			
			output.close();
			
			//write body length
			int bodyLength = buffer.writerIndex() - bodyLengthOffset - 4;
			int savedWriterIndex = buffer.writerIndex();
			buffer.writerIndex(bodyLengthOffset);
			buffer.writeInt(bodyLength);
			buffer.writerIndex(savedWriterIndex);
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
			throw new ProtocalException("encode msg failed",e, msg.getId());
		}
	
		
	}
	
	private void encodeRequestBody(Invoke invoke, ObjectOutput output) {
		
		int argLength = invoke.getArgs() == null ? 0 : invoke.getArgs().length;
		output.writeString(invoke.getInterfaceName());
		output.writeString(invoke.getVersion());
		output.writeString(invoke.getMethodName());
		output.writeInt(argLength);

		if(argLength == 0) {
			return;
		}
		
		for (int i= 0; i <  argLength; i++) {
			output.writeString(invoke.getParameterTypeNames()[i]);
			output.writeObject(invoke.getArgs()[i]);
		}
	}


	@Override
	public Message decode(ByteBuffer buffer) throws ProtocalException {
		if(buffer.readableBytes() < HEAD_LENGTH) {
			return Message.NEED_MORE;
		}
		buffer.markReaderIndex();
		
		short magic = buffer.readShort();
		if(magic != MAGIC) {
			buffer.resetReaderIndex();
			return null;
		}
		
		int flag = buffer.readByte();
		int status = buffer.readByte();
		long msgId = buffer.readLong();
		int bodyLength = buffer.readInt();
		
		if(buffer.readableBytes() < bodyLength) {
			buffer.resetReaderIndex();
			return Message.NEED_MORE;
		}
		
		boolean isHeartbeat = (flag & FLAG_EVENT) > 0;	
		boolean isRequest = (flag & FLAG_REQUEST) > 0;
		boolean isTwoWay = (flag & FLAG_TWOWAY) > 0 ;
		int serializerId = (flag & SERIALIZATION_MASK);
		boolean isErrorMsg = (status != STATUS_OK);
		
		if (isHeartbeat) {
			if (isTwoWay) {
				return HeartbeatMessage.PING;
			} else {
				return HeartbeatMessage.PONG;
			}
		}
		
		try {
			//decode body
			Serializer serializer = serializers[serializerId];	
			ObjectInput input = serializer.createObjectInput(new ByteBufferInputStream(buffer, bodyLength));
			Message msg = null;
			if(isErrorMsg) {
				String errorMsg = input.readString();
				msg =   new InvokeResult(new ServerErrorException(errorMsg), msgId, null);
			}
			else if(isRequest) {
				msg =  decodeRequestBody(msgId,serializer ,input);
			}
			else {
				Object result = null;
				String resultClassName = input.readString();
				if(!NULL.equals(resultClassName)) {
					Class<?> resultClass = ReflectionUtil.findClass(resultClassName);
					//generic invoke result will use HashMap decode
					if(resultClass == null) {
						resultClass = HashMap.class;
					}
				    result = input.readObject(resultClass);
				}
				
				msg= new InvokeResult(result, msgId, serializer);
			}
			input.close();
			return msg;
			
			
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
			throw new ProtocalException("decode msg  failed!" ,e , msgId);
		}
	}
	

	

	private Invoke decodeRequestBody(long msgId, Serializer serializer,ObjectInput input) throws ClassNotFoundException {
		
		String interfaceName = input.readString();
		String version =  input.readString();
		String methodName =  input.readString();
		int argsLength = input.readInt();
		
		
		if(argsLength == 0) {
			Invoke invoke = new Invoke(interfaceName, version, methodName,serializer, null, EMPTY_TYPE_ARRAY);
			invoke.setId(msgId);
			return invoke;
		}
		
		Class<?>[] parameterTypes = new Class[argsLength];
		Object[]  args = new Object[argsLength];
		
		for (int i = 0; i < argsLength; i++) {
			String parameterClassName = input.readString();
			parameterTypes[i] = ReflectionUtil.findClass(parameterClassName);
			args[i] = input.readObject(parameterTypes[i]);
		}

		Invoke invoke = new Invoke(interfaceName, version, methodName, serializer,args , parameterTypes);
		invoke.setId(msgId);
		return invoke;
	}

	@Override
	public ServiceRegistry getRegistry() {
		return registry;
	}
	
}
