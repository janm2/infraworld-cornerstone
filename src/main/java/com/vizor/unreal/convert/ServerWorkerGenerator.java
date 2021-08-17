package com.vizor.unreal.convert;

import static com.vizor.unreal.tree.CppRecord.Residence.Cpp;
import static com.vizor.unreal.tree.CppType.plain;
import static com.vizor.unreal.tree.CppType.wildcardGeneric;
import static com.vizor.unreal.tree.CppType.Kind.Class;
import static com.vizor.unreal.tree.CppType.Kind.Struct;
import static com.vizor.unreal.convert.CodeGenerator.conduitName;
import static com.vizor.unreal.convert.CodeGenerator.conduitType;
import static com.vizor.unreal.convert.CodeGenerator.arrayType;
import static com.vizor.unreal.convert.CodeGenerator.dataName;
import static com.vizor.unreal.convert.CodeGenerator.callDataType;
import static com.vizor.unreal.convert.CodeGenerator.initFunctionName;
import static com.vizor.unreal.convert.CodeGenerator.supressSuperString;
import static com.vizor.unreal.convert.CodeGenerator.updateFunctionName;
import static com.vizor.unreal.convert.CodeGenerator.shutdownName;
import static com.vizor.unreal.convert.CodeGenerator.reqWithTag;
import static com.vizor.unreal.convert.CodeGenerator.rspWithTag;
import static java.lang.String.join;
import static java.lang.System.lineSeparator;
import static java.text.MessageFormat.format;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.squareup.wire.schema.internal.parser.ProtoFileElement;
import com.squareup.wire.schema.internal.parser.RpcElement;
import com.squareup.wire.schema.internal.parser.ServiceElement;
import com.vizor.unreal.provider.TypesProvider;
import com.vizor.unreal.tree.CppClass;
import com.vizor.unreal.tree.CppField;
import com.vizor.unreal.tree.CppFunction;
import com.vizor.unreal.tree.CppType;

public class ServerWorkerGenerator extends WorkerGenerator
{
	private static final CppType genericParentType = wildcardGeneric("TServiceRpcWorker", Class, 1);

	ServerWorkerGenerator(List<ServiceElement> services, TypesProvider provider, ProtoFileElement parse)
    {
        super(services,provider, parse);
    }
	
	
	private String getServiceName(final ServiceElement service)
	{
		return getPackageNamespaceString() + service.name() + "::AsyncService";
	}
	
	private CppType createServiceType(final ServiceElement service)
    {
        return plain(getServiceName(service), Struct);
    }
	
	@Override
	public List<CppClass> genClass()
	{
		return services.stream().map(this::genSingle).collect(toList());
	}
	
	private CppClass genSingle(final ServiceElement service)
    {
		final List<CppField> cppFields = extractConduits(service, reqWithTag, rspWithTag);
	    final List<CppField> fields = new ArrayList<>(cppFields);
	
	    final List<CppFunction> methods = new ArrayList<>();
	    
	    methods.add(createServerInitializer(service, fields));
	    methods.add(createUpdate(service, fields));
	    methods.add(createShutdown(service, fields));
		
		final CppType classType = plain(service.name() + "RpcSeverWorker", Class);
		
		final CppType serviceType = createServiceType(service);
		
		fields.addAll(getArrays(service,serviceType));
		
		final CppType parentType = genericParentType.makeGeneric(serviceType);
		
		final CppClass clientClass = new CppClass(classType, parentType, fields, methods);

        clientClass.setResidence(Cpp);
        clientClass.enableAnnotations(false);

        return clientClass;
    }
	
	private List<CppField> getArrays(final ServiceElement service, final CppType serviceType)
	{
		 return service.rpcs().stream()
		            .map(rpc -> {
		                // Extract conduits (bidirectional queues)
		            	
		            	final CppType requset = plain(getPackageNamespaceString() +  rpc.requestType(), Struct);
		            	final CppType response = plain(getPackageNamespaceString() + rpc.responseType(), Struct);
		            	
		            	final CppType compiledGenericCallData = callDataType.makeGeneric(serviceType, requset, response);
		            	
		            	
		                final CppType compiledGenericArray = arrayType.makeGeneric(
		                		compiledGenericCallData.makePtr()
		                );

		                final CppField conduit = new CppField(compiledGenericArray, rpc.name() + dataName);
		                conduit.enableAnnotations(false);
		                
		                return conduit;
		            })
		            .collect(toList());
	}
	
	private CppFunction createServerInitializer(final ServiceElement service, final List<CppField> fields)
    {
        final CppFunction initServer = new CppFunction(initFunctionName, boolType);

        final StringBuilder sb = new StringBuilder(supressSuperString(initFunctionName));

        final String createProducerPattern = "connection::CreateServer<{0}>(this, Server, Que, &Service);";
        
        sb.append(format(
        		createProducerPattern,
        		getServiceName(service)
        		))
                .append(lineSeparator()).append(lineSeparator());

        final String acquireProducerPattern = "{0}->AcquireRequestsProducer();";

        // Acquire all required conduits
        fields.forEach(a -> sb.append(format(acquireProducerPattern, a.getName())).append(lineSeparator()));
        
        
        final String dataCallPattern = "CreateDataCall<{2}{0}, {2}{1}>({3}, {4}, &{5}::{6});";
        
        sb.append(lineSeparator());
        
        sb.append("// Start the tag handoff, by creating first one").append(lineSeparator());
        
        
        ImmutableList<RpcElement> rpcs = service.rpcs();
        
        for(int i = 0; i < rpcs.size(); i++)
        {
        	sb.append(format(
        			dataCallPattern, 
        			rpcs.get(i).requestType(),				//{0}
        			rpcs.get(i).responseType(), 			//{1}
        			getPackageNamespaceString(),			//{2}
        			i,										//{3}
        			rpcs.get(i).name() + dataName,			//{4}
        			getServiceName(service),				//{5}
        			"Request" + rpcs.get(i).name()			//{6}
        			)).append(lineSeparator());
        }

        sb.append(lineSeparator()).append("return true;");

        initServer.setBody(sb.toString());
        initServer.isOverride = true;
        initServer.enableAnnotations(false);

        return initServer;
    }
	
	private CppFunction createUpdate(final ServiceElement service, final List<CppField> fields)
    {
        final CppFunction update = new CppFunction(updateFunctionName, voidType);
        final StringBuilder sb = new StringBuilder(supressSuperString(updateFunctionName));

        // @see https://stackoverflow.com/questions/1187093/can-i-escape-braces-in-a-java-messageformat
        final String respondPattern = join(lineSeparator(), asList(
            "if (!{0}->IsEmpty())",
            "'{'",
            "	{1} WrappedResponse;",
            "	{0}->Dequeue(WrappedResponse);",
            "",
            "	AsyncRespond<{5}{2}, {3}, {5}{4}>(WrappedResponse);",
            "'}'"
        ));
        
        sb.append("// first respond to clients if any response is ready").append(lineSeparator());
        
        for (int i = 0; i < fields.size(); i++)
        {
            final RpcElement rpc = service.rpcs().get(i);
            final CppField field = fields.get(i);
            final List<CppType> genericParams = field.getType().getGenericParams();

            sb.append(format(
            	respondPattern,
            	field.getName(),					//{0}
                genericParams.get(1),				//{1}
                rpc.requestType(),					//{2}
                provider.get(rpc.responseType()),	//{3}
                rpc.responseType(),					//{4}
                getPackageNamespaceString()			//{5}
            )).append(lineSeparator()).append(lineSeparator());
        }
        
        sb.append("void* tag = nullptr;").append(lineSeparator()).append(lineSeparator());
   
        sb.append("// Wait for next request from client, blocks thread for some time").append(lineSeparator());
        
        sb.append("if (WaitForNext(tag))").append(lineSeparator());
        sb.append("{").append(lineSeparator());
        
        sb.append("	if (tag)").append(lineSeparator());
        sb.append("	{").append(lineSeparator());
        
        
        sb.append("		IndexHolder* holder = static_cast<IndexHolder*>(tag);").append(lineSeparator()).append(lineSeparator());
        
        final String WaitPattern = join(lineSeparator(), asList(
        		"		if(holder->Type == {8})",
        		"		'{'",
                "			{1} WrappedRequest = AsyncProcess<{2}, {5}{3}, {5}{4}>(",
                "							holder, {9}, &{6}::{7});",
                "			",
                "			if (WrappedRequest.Valid)",
                "			'{'",
                "				{0}->Enqueue(WrappedRequest);",
                "			'}'",
                "		'}'"
         ));
        
        for (int i = 0; i < fields.size(); i++)
        {
            final RpcElement rpc = service.rpcs().get(i);
            final CppField field = fields.get(i);
            final List<CppType> genericParams = field.getType().getGenericParams();

            sb.append(format(
            	WaitPattern,
            	field.getName(),					//{0}
                genericParams.get(0),				//{1}
                provider.get(rpc.requestType()),	//{2}
                rpc.requestType(),					//{3}
                rpc.responseType(),					//{4}
                getPackageNamespaceString(),		//{5}
                getServiceName(service),			//{6}
    			"Request" + rpc.name(),				//{7}
    			i,									//{8}
    			rpc.name() + dataName				//{9}
            )).append(lineSeparator()).append(lineSeparator());
        }

        
        sb.append("	}").append(lineSeparator());
        
        sb.append("}").append(lineSeparator());
        
        update.setBody(sb.toString());
        update.isOverride = true;
        update.enableAnnotations(false);
        return update;
    }
	
	private CppFunction createShutdown(final ServiceElement service, final List<CppField> fields)
	{
		final CppFunction update = new CppFunction(shutdownName, voidType);
		final StringBuilder sb = new StringBuilder(supressSuperString(shutdownName));
		
		sb.append("Server->Shutdown();").append(lineSeparator());
		sb.append("Que->Shutdown();").append(lineSeparator());
		sb.append("DrainQue();").append(lineSeparator()).append(lineSeparator());
		
		final String emptyPattern = join(lineSeparator(), asList(
        		"for (int i = 0; i < {0}.Num(); i++)",
        		"'{'",
        		"	if({0}[i])",
        		"	'{'",
        		"		{0}[i]->Finish();",
                "	'}'",
                "'}'",
                "",
                "{0}.Empty();"
         ));
		
		for (int i = 0; i < fields.size(); i++)
        {
			sb.append(format(
					emptyPattern,
					service.rpcs().get(i).name() + dataName
			)).append(lineSeparator()).append(lineSeparator());
        }
		
		
		update.setBody(sb.toString());
        update.isOverride = true;
        update.enableAnnotations(false);
        return update;
	}
	
}
