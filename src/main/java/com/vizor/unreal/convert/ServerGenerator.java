package com.vizor.unreal.convert;

import static com.vizor.unreal.tree.CppType.plain;
import static com.vizor.unreal.tree.CppType.Kind.Class;
import static com.vizor.unreal.tree.CppType.Kind.Struct;
import static java.lang.String.join;
import static java.lang.System.lineSeparator;
import static java.text.MessageFormat.format;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


import com.squareup.wire.schema.internal.parser.RpcElement;
import com.squareup.wire.schema.internal.parser.ServiceElement;
import com.vizor.unreal.provider.TypesProvider;
import com.vizor.unreal.tree.CppClass;
import com.vizor.unreal.tree.CppDelegate;
import com.vizor.unreal.tree.CppField;
import com.vizor.unreal.tree.CppFunction;
import com.vizor.unreal.tree.CppType;
import com.vizor.unreal.util.Tuple;

public class ServerGenerator extends CodeGenerator
{	
	 
	 ServerGenerator(final ServiceElement service, final TypesProvider provider, final CppType workerType) 
	 {
		 super(service, provider, workerType);
		 conduits = genConduits(reqWithTag, rspWithTag);
	 }
	 
	 
	@Override
    public String getClassName()
    {
    	return "RpcServer";
    }
	 
	
    @Override
    public CppClass genClass()
    {
        final List<CppFunction> methods = new ArrayList<>();
        methods.add(genInitialize());
        //methods.addAll(genProcedures());

        final List<CppField> fields = new ArrayList<>(conduits);
        
        List<CppField> functionDefinitions = getFunctionDefinitionFields();
        List<CppField> functionFields = getFunctionFields(functionDefinitions);
        fields.addAll(functionDefinitions);
        fields.addAll(functionFields);
        
        methods.add(genUpdate(functionFields));
        
        //fields.addAll(delegates.stream().map(Tuple::second).collect(toList()));

        return new CppClass(dispatcherType, parentType, fields, methods);
    }
    
    @Override
    public List<CppDelegate> getDelegates()
    {
    	return new ArrayList<>();
    }
    
    private CppFunction genInitialize()
    {
        final StringBuilder sb = new StringBuilder(supressSuperString(initFunctionName));
        final String cName = workerType.getName();

        final String workerVariableName = "Worker";

        sb.append(cName).append("* const ").append(workerVariableName).append(" = new ").append(cName).append("();");
        sb.append(lineSeparator()).append(lineSeparator());

        conduits.forEach(f -> {
            sb.append(workerVariableName).append("->").append(f.getName()).append(" = &");

            sb.append(f.getName()).append(';').append(lineSeparator());
            sb.append(f.getName()).append('.').append("AcquireResponsesProducer();");

            sb.append(lineSeparator()).append(lineSeparator());
        });

        sb.append("InnerWorker = TUniquePtr<RpcWorker>(").append(workerVariableName).append(");");
        sb.append(lineSeparator()).append(lineSeparator());

        final CppFunction init = new CppFunction(initFunctionName, voidType);

        init.isOverride = true;
        init.setBody(sb.toString());
        init.enableAnnotations(false);

        return init;
    }
    
    public List<CppField> getFunctionDefinitionFields()
    {
    	final List<RpcElement> rpcs = service.rpcs();
    	
    	 return rpcs.stream()
	    	.map(e -> {
	    		
	    		
	            final CppType request = provider.get(e.requestType());
	            final CppType response = provider.get(e.responseType());
	    		
	            final CppType functionSigniture =  plain(response.getName(), Struct);
	            functionSigniture.addFunctionParams(request);
	            
	            final CppType functionTypedef = tfunctionType.makeGeneric(functionSigniture);
	    		
	    		final CppField f = new CppField(functionTypedef, functionName + e.name());
	            f.enableAnnotations(false);
	
	            return f;
	    	 })
	    	.collect(toList());
    }
    
    public List<CppField> getFunctionFields(List<CppField> functionDefinitions)
    {
    	final List<RpcElement> rpcs = service.rpcs();
    	
    	List<CppField> functionFields = new ArrayList<>();
    	

    	for (int i = 0; i < functionDefinitions.size(); i++)
        {
    		final CppType basedOnFunctionDef = plain(functionDefinitions.get(i).getName(), Struct);
    		
    		final CppField f = new CppField(basedOnFunctionDef, rpcs.get(i).name());
            f.enableAnnotations(false);
            
            functionFields.add(f);
        }
    	
    	
    	return functionFields;
    }
    
    private CppFunction genUpdate(List<CppField> functionFields)
    {
        final StringBuilder sb = new StringBuilder(supressSuperString(updateFunctionName));
    	
        final String dequeuePattern = join(lineSeparator(), asList(
            "if (!{0}.IsEmpty())",
            "'{'",
            "    {1} RequestWrapper;",
            "    while ({0}.Dequeue(RequestWrapper))",
            "	 '{'",
            "        if ({4})",
            "		'{'",
            "    		{2} Reponse = {4}(RequestWrapper.Request);",
            "    		{3} WrappedResponse(Reponse, RequestWrapper.Tag);",
            "    		{0}.Enqueue(WrappedResponse);",
            "	 	'}'",
            "	 '}'",
            "'}'"
        ));

        /*
        // No need to call Super::HierarchicalUpdate(), it isn't required by design
		if (!ProcessCommandsConduit.IsEmpty())
		{
			TRequestWithTag<FVsarController_CommandsRequest> Request;
			while (ProcessCommandsConduit.Dequeue(Request))
			{
				if (FunctionProcessCommands)
				{
					FVsarController_CommandsResponse Reponse = FunctionProcessCommands(Request.Request);
					TResponseWithTag<FVsarController_CommandsResponse> WrappedResponse(Reponse, Request.Tag);
					ProcessCommandsConduit.Enqueue(WrappedResponse);
				}
			}
		}    
         */
        
        for (int i = 0; i < conduits.size(); i++)
        {
            final CppField conduit = conduits.get(i);
            final List<CppType> genericParams = conduit.getType().getGenericParams();

            sb.append(format(
            		dequeuePattern,
            		conduit.getName(),										//{0}
                    genericParams.get(0),									//{1}
                    genericParams.get(1).getGenericParams().get(0),			//{2}
                    genericParams.get(1),									//{3}
                    functionFields.get(i).getName()							//{4}
            		)).append(lineSeparator()).append(lineSeparator());
        }

        final CppFunction update = new CppFunction(updateFunctionName, voidType);
        
        update.setBody(sb.toString());
        update.isOverride = true;
        update.enableAnnotations(false);

        return update;
    }
}
