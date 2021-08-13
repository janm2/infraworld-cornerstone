package com.vizor.unreal.convert;

import static com.vizor.unreal.tree.CppType.plain;
import static com.vizor.unreal.tree.CppType.wildcardGeneric;
import static com.vizor.unreal.tree.CppType.wildcardGenericTypedef;
import static com.vizor.unreal.tree.CppType.Kind.Class;
import static com.vizor.unreal.tree.CppType.Kind.Struct;
import static java.lang.System.lineSeparator;
import static java.util.stream.Collectors.toList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.squareup.wire.schema.internal.parser.RpcElement;
import com.squareup.wire.schema.internal.parser.ServiceElement;
import com.vizor.unreal.config.Config;
import com.vizor.unreal.provider.TypesProvider;
import com.vizor.unreal.tree.CppArgument;
import com.vizor.unreal.tree.CppClass;
import com.vizor.unreal.tree.CppDelegate;
import com.vizor.unreal.tree.CppField;
import com.vizor.unreal.tree.CppType;
import com.vizor.unreal.util.Tuple;

/**
 *  Abstract class for client/server common functions
 */

public abstract class CodeGenerator 
{
    // Special structures, wrapping requests and responses:
    public static final CppType reqWithCtx = wildcardGeneric("TRequestWithContext", Struct, 1);
    public static final CppType rspWithSts = wildcardGeneric("TResponseWithStatus", Struct, 1);
    public static final CppType reqWithTag = wildcardGeneric("TRequestWithTag", Struct, 1);
    public static final CppType rspWithTag = wildcardGeneric("TResponseWithTag", Struct, 1);
    public static final CppType conduitType = wildcardGeneric("TConduit", Struct, 2);
    public static final CppType tfunctionType = wildcardGenericTypedef("TFunction", Struct, 1);
    public static final CppType arrayType = wildcardGeneric("TArray", Struct, 1);
    public static final CppType callDataType = wildcardGeneric("CallData", Struct, 3);
    
    public static final String functionName = "Function";
    public static final String conduitName = "Conduit";
    public static final String dataName = "Data";
    public static final String updateFunctionName = "HierarchicalUpdate";
    public static final String initFunctionName = "HierarchicalInit";
    public static final String shutdownName = "HierarchicalShutdown";
	
    public static final CppArgument contextArg = new CppArgument(plain("FGrpcClientContext", Struct).makeRef(), "Context");
    
    // Frequently used string literals:
	protected static final String companyName = Config.get().getCompanyName();
	protected static final String rpcRequestsCategory = companyName + "|RPC Requests|";
    protected static final String rpcResponsesCategory = companyName + "|RPC Responses|";
    protected static final String eventPrefix = "Event";
    protected static final String eventTypePrefix = "F" + eventPrefix;
    
	protected final CppType dispatcherType;
	protected final CppType parentType;
	protected final CppType workerType;
	
	protected final CppType boolType;
	protected final CppType voidType;
	
	protected final Map<String, Tuple<CppType, CppType>> requestsResponses;
	protected List<CppField> conduits;
	
	protected final ServiceElement service;
	protected final TypesProvider provider;
	
	
	CodeGenerator(final ServiceElement service, final TypesProvider provider, final CppType workerType)
    {
		 this.dispatcherType = plain("U" + service.name() + getClassName(), Class);
		 this.parentType = plain("U"+ getClassName(), Class);
		 this.workerType = workerType;
		 this.service = service;
		 this.provider = provider;
		 
	     boolType = provider.getNative(boolean.class);
	     voidType = provider.getNative(void.class);
	     
	     final List<RpcElement> rpcs = service.rpcs();
	     
	     requestsResponses = new HashMap<>(rpcs.size());
	        rpcs.forEach(r -> requestsResponses.put(r.name(),
	            Tuple.of(
	                provider.get(r.requestType()),
	                provider.get(r.responseType())
	            )
	        ));
    }
	
	public abstract CppClass genClass();
	
	public abstract List<CppDelegate> getDelegates();

	public abstract String getClassName();
	
    static String supressSuperString(final String functionName)
    {
        return "// No need to call Super::" + functionName + "(), it isn't required by design" + lineSeparator();
    }
    
    public List<CppField> genConduits(CppType reqType, CppType resType)
    {
        return requestsResponses.entrySet().stream()
            .map(e -> {
                final CppType compiled = e.getValue().reduce((req, rsp) -> conduitType.makeGeneric(
                		reqType.makeGeneric(req),
                		resType.makeGeneric(rsp))
                );
                final CppField f = new CppField(compiled, e.getKey() + conduitName);
                f.enableAnnotations(false);

                return f;
            })
            .collect(toList());
    }
    
}
