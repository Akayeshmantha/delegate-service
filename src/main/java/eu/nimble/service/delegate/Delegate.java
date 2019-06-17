package eu.nimble.service.delegate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.Response;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletContextEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.net.URI;

/**
 * Delegate service.
 *
 * Created by Nir Rozenbaum (nirro@il.ibm.com) 05/30/2019.
 */
@ApplicationPath("/")
@Path("/")
public class Delegate implements ServletContextListener {
    private static final int REQ_TIMEOUT_SEC = 3;

    private static Logger logger = LogManager.getLogger(Delegate.class);

    private static ApplicationInfoManager applicationInfoManager;
    private static EurekaClient eurekaClient;
    private static String vipAddress = "eu.nimble.delegate";
    
    private static Client httpClient;
    
    private static String frontendServiceUrl;
    private static String indexingServiceUrl;
    private static int indexingServicePort;
    
    private static String getItemFieldsPath = "/item/fields";
    private static String getItemFieldsLocalPath = "/item/fields/local";
    private static String getPartyFieldsPath = "/party/fields";
    private static String getPartyFieldsLocalPath = "/party/fields/local";
    private static String postItemSearchPath = "/item/search";
    private static String postItemSearchLocalPath = "/item/search/local";
    private static String postPartySearchPath = "/party/search";
    private static String postPartySearchLocalPath = "/party/search/local";
    
    private static ObjectMapper mapper = new ObjectMapper();
    private static JsonParser jsonParser = new JsonParser();
    
    /***********************************   Servlet Context   ***********************************/
    
    public void contextInitialized(ServletContextEvent arg0) 
    {
    	try {
    		frontendServiceUrl = System.getenv("FRONTEND_URL");
    		indexingServiceUrl = System.getenv("INDEXING_SERVICE_URL");
    		indexingServicePort = Integer.parseInt(System.getenv("INDEXING_SERVICE_PORT"));
    	}
    	catch (Exception ex) {
    		logger.warn("env vars are not set as expected");
    	}
    	
        logger.info("Delegate service is being initialized (vipAddress = " + vipAddress + "), with frontend service param = " + frontendServiceUrl 
        													+ ", indexing service param = " + indexingServiceUrl + ":" + indexingServicePort + "...");
        
        httpClient = ClientBuilder.newClient();

        if (!initEureka()) {
            logger.error("Failed to initialize Eureka client");
            return;
        }
        logger.info("Delegate service has been initialized");
    }

    @Override
    public void contextDestroyed(ServletContextEvent arg0) {
        applicationInfoManager.setInstanceStatus(InstanceInfo.InstanceStatus.DOWN);
        logger.info("Delegate service has been destroyed");
    }
    
    /***********************************   Servlet Context - END   ***********************************/
    
    
    @GET
    @Path("/")
    public Response hello() {
        return Response.status(Status.OK)
        			   .type(MediaType.TEXT_PLAIN)
        			   .entity("Hello from Delegate Service\n")
        			   .build();
    }
    
    
    /***********************************   indexing-service/item/fields   ***********************************/
    
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/item/fields")
    public Response federatedGetItemFields(@Context HttpHeaders headers, @QueryParam("fieldName") List<String> fieldName) {
    	logger.info("called federated get item fields");
    	HashMap<String, List<String>> queryParams = new HashMap<String, List<String>>();
    	if (fieldName != null && !fieldName.isEmpty()) {
    		queryParams.put("fieldName", fieldName);
        }
    	logger.info("query params: " + queryParams.toString());
    	HashMap<ServiceEndpoint, String> resultList = sendGetRequestToAllServices(getItemFieldsLocalPath, queryParams);
    	List<Object> aggregatedResults = mergeGetResponsesByFieldName(resultList);
    	
    	return Response.status(Response.Status.OK)
    				   .type(MediaType.APPLICATION_JSON)
    				   .entity(aggregatedResults)
    				   .build();
    }
    
    // a REST call that should be used between delegates. 
    // the origin delegate sends a request and the target delegate will perform the query locally.
    // TODO add authorization header to make sure the caller is a delegate rather than a human (after adding federation identity service)
    @GET
    @Path("/item/fields/local")
    public Response getItemFields(@Context HttpHeaders headers, @QueryParam("fieldName") List<String> fieldName) {
        HashMap<String, List<String>> queryParams = new HashMap<String, List<String>>();
        queryParams.put("fieldName", fieldName);
        URI uri = buildUri(indexingServiceUrl, indexingServicePort, getItemFieldsPath, queryParams);
        logger.info("got a request to endpoint " + getItemFieldsLocalPath + ", forwarding to " + uri.toString());
        
        Response response = httpClient.target(uri.toString()).request().get();
        if (response.getStatus() >= 200 && response.getStatus() <= 300) {
        	String data = response.readEntity(String.class);
            return Response.status(Status.OK)
            			   .entity(data)
            			   .type(MediaType.APPLICATION_JSON)
            			   .header("frontendServiceUrl", frontendServiceUrl)
            			   .build();
        }
        else {
        	return response;
        }
    }
    
    /***********************************   indexing-service/item/fields - END   ***********************************/
    

    /***********************************   indexing-service/party/fields   ***********************************/
    
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/party/fields")
    public Response federatedGetPartyFields(@Context HttpHeaders headers, @QueryParam("fieldName") List<String> fieldName) {
    	logger.info("called federated get party fields");
    	HashMap<String, List<String>> queryParams = new HashMap<String, List<String>>();
    	if (fieldName != null && !fieldName.isEmpty()) {
    		queryParams.put("fieldName", fieldName);
        }
    	logger.info("query params: " + queryParams.toString());
    	HashMap<ServiceEndpoint, String> resultList = sendGetRequestToAllServices(getPartyFieldsLocalPath, queryParams);
    	List<Object> aggregatedResults = mergeGetResponsesByFieldName(resultList);
    	
    	return Response.status(Response.Status.OK)
    				   .type(MediaType.APPLICATION_JSON)
    				   .entity(aggregatedResults)
    				   .build();
    }
    
    // a REST call that should be used between delegates. 
    // the origin delegate sends a request and the target delegate will perform the query locally.
    // TODO add authorization header to make sure the caller is a delegate rather than a human (after adding federation identity service)
    @GET
    @Path("/party/fields/local")
    public Response getPartyFields(@Context HttpHeaders headers, @QueryParam("fieldName") List<String> fieldName) {
        HashMap<String, List<String>> queryParams = new HashMap<String, List<String>>();
        queryParams.put("fieldName", fieldName);
        URI uri = buildUri(indexingServiceUrl, indexingServicePort, getPartyFieldsPath, queryParams);
        logger.info("got a request to endpoint " + getPartyFieldsLocalPath + ", forwarding to " + uri.toString());
        
        Response response = httpClient.target(uri.toString()).request().get();
        if (response.getStatus() >= 200 && response.getStatus() <= 300) {
        	String data = response.readEntity(String.class);
            return Response.status(Status.OK)
            			   .entity(data)
            			   .type(MediaType.APPLICATION_JSON)
            			   .header("frontendServiceUrl", frontendServiceUrl)
            			   .build();
        }
        else {
        	return response;
        }
    }
    
    /***********************************   indexing-service/party/fields - END   ***********************************/
    
    
    /***********************************   indexing-service/item/search   ***********************************/   
     /* @throws IOException 
     * @throws JsonMappingException 
     * @throws JsonParseException 
    */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/item/search")
    public Response federatedPostItemSearch(Map<String, Object> body) throws JsonParseException, JsonMappingException, IOException {
    	logger.info("called federated post item search");
    	//initialize result from the request body
    	IndexingServiceResult indexingServiceResult = new IndexingServiceResult(Integer.parseInt(body.get("rows").toString()), 
    																			Integer.parseInt(body.get("start").toString())); 
    	HashMap<ServiceEndpoint, String> resultList = getPostItemSearchAggregatedResults(body);
    	
    	for (ServiceEndpoint endpoint : resultList.keySet()) {
    		String results = resultList.get(endpoint);
    		indexingServiceResult.addEndpointResponse(endpoint, results, endpoint.getId().equals(applicationInfoManager.getInfo().getId()));
    	}
    	
    	return Response.status(Response.Status.OK)
    								   .type(MediaType.APPLICATION_JSON)
    								   .entity(indexingServiceResult.getFinalResult())
    								   .build();
    }
    
    @SuppressWarnings("unchecked")
	private HashMap<ServiceEndpoint, String> getPostItemSearchAggregatedResults(Map<String, Object> body) throws JsonParseException, JsonMappingException, IOException {
    	int requestedPageSize = Integer.parseInt(body.get("rows").toString()); // save it before manipulating
    	// manipulate body in order to get results from all delegates.
    	List<ServiceEndpoint> endpointList = getEndpointsFromEureka();
    	body.put("rows", 0); // send dummy request just to get totalElements fields from all delegates
    	HashMap<ServiceEndpoint, String> dummyResultList = sendPostRequestToAllServices(endpointList, postItemSearchLocalPath, body);
    	
    	int sumTotalElements = 0;
    	final LinkedHashMap<ServiceEndpoint, Integer> totalElementPerEndpoint = new LinkedHashMap<ServiceEndpoint, Integer>();
    	for (Entry<ServiceEndpoint, String> entry : dummyResultList.entrySet()) {
    		Map<String, Object> json = mapper.readValue(entry.getValue(), Map.class);
    		int totalElementForEndpoint = Integer.parseInt(json.get("totalElements").toString());
    		totalElementPerEndpoint.put(entry.getKey(), totalElementForEndpoint);
    		sumTotalElements += totalElementForEndpoint;
    	}
    	if (sumTotalElements <= requestedPageSize) {
    		body.put("rows", requestedPageSize); 
    		return sendPostRequestToAllServices(endpointList, postItemSearchLocalPath, body);
    	}
    	// else, we need to decide how many results we want from each delegate
   
    	int totalElementsAggregated = 0;
    	HashMap<ServiceEndpoint, String> aggregatedResults = new LinkedHashMap<ServiceEndpoint, String>();
    	for (ServiceEndpoint endpoint : endpointList) {
    		int localTotalElements = Math.round(totalElementPerEndpoint.get(endpoint) / ((float)sumTotalElements))*requestedPageSize;
    		
    	}
    	
    	return aggregatedResults;
    }
    
    // a REST call that should be used between delegates. 
    // the origin delegate sends a request and the target delegate will perform the query locally.
    // TODO add authorization header to make sure the caller is a delegate rather than a human (after adding federation identity service)
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/item/search/local")
    public Response postItemSearch(Map<String, Object> body) {
    	// if fq list in the request body contains field name that doesn't exist in local instance don't do any search, return empty result
    	Set<String> localFieldNames = getLocalFieldNamesFromIndexingSerivce();
    	if (doesFqListContainFieldNameNotFromLocal(body, localFieldNames)) {
    		return Response.status(Response.Status.OK).type(MediaType.TEXT_PLAIN).entity("").build();
    	}
    	// remove from body.facet.field all fieldNames that doesn't exist in local instance 
    	removeNonExistingFieldNamesFromBody(body, localFieldNames);
    	
        URI uri = buildUri(indexingServiceUrl, indexingServicePort, postItemSearchPath, null);
        
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<String, Object>();
        headers.add("Content-Type", "application/json");
        
        return forwardPostRequest(postItemSearchLocalPath, uri.toString(), body, headers);
    }
    
    /***********************************   indexing-service/item/search - END   ***********************************/
    
    
    /***********************************   indexing-service/party/search   ***********************************/
     /* @throws IOException 
     * @throws JsonMappingException 
     * @throws JsonParseException 
    */
	@POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/party/search")
    public Response federatedPostPartySearch(Map<String, Object> body) throws JsonParseException, JsonMappingException, IOException {
    	logger.info("called federated post party search");
    	List<ServiceEndpoint> endpointList = getEndpointsFromEureka();
    	//initialize result from the request body
    	IndexingServiceResult indexingServiceResult;
    	if (body.get("start") != null) {
    	indexingServiceResult = new IndexingServiceResult(Integer.parseInt(body.get("rows").toString()), 
														  Integer.parseInt(body.get("start").toString()));
    	}
    	else {
    		indexingServiceResult = new IndexingServiceResult(Integer.parseInt(body.get("rows").toString()), 0);
    	}
    	
    	HashMap<ServiceEndpoint, String> resultList = sendPostRequestToAllServices(endpointList, postPartySearchLocalPath, body);
    	
    	for (ServiceEndpoint endpoint : resultList.keySet()) {
    		String results = resultList.get(endpoint);
    		indexingServiceResult.addEndpointResponse(endpoint, results, endpoint.getId().equals(applicationInfoManager.getInfo().getId()));
    	}
    	return Response.status(Response.Status.OK)
    								   .type(MediaType.APPLICATION_JSON)
    								   .entity(indexingServiceResult.getFinalResult())
    								   .build();
    	
    }
    
    // a REST call that should be used between delegates. 
    // the origin delegate sends a request and the target delegate will perform the query locally.
    // TODO add authorization header to make sure the caller is a delegate rather than a human (after adding federation identity service)
	@POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/party/search/local")
    public Response postPartySearch(Map<String, Object> body) {
    	// if fq list in the request body contains field name that doesn't exist in local instance don't do any search, return empty result
    	Set<String> localFieldNames = getLocalFieldNamesFromIndexingSerivce();
    	if (doesFqListContainFieldNameNotFromLocal(body, localFieldNames)) {
    		return Response.status(Response.Status.OK).type(MediaType.TEXT_PLAIN).entity("").build();
    	}
    	// remove from body.facet.field all fieldNames that doesn't exist in local instance 
    	removeNonExistingFieldNamesFromBody(body, localFieldNames);
    	
    	URI uri = buildUri(indexingServiceUrl, indexingServicePort, postPartySearchPath, null);
        
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<String, Object>();
        headers.add("Content-Type", "application/json");
        
        return forwardPostRequest(postPartySearchLocalPath, uri.toString(), body, headers);
    }
    
    /***********************************   indexing-service/party/search - END   ***********************************/
    
    
    /***********************************   indexing-service extra logic   ***********************************/
    
    // if field name exists in more than one instance, putting the entry just once, ignoring doc count field
    private List<Object> mergeGetResponsesByFieldName(HashMap<ServiceEndpoint, String> resultList) {
    	List<Object> aggregatedResults = new LinkedList<Object>();
    	
    	for (String results : resultList.values()) {
    		if (results == null || results.isEmpty()) {
				continue;
			}
    		List<Map<String, Object>> json;
			try {
				json = mapper.readValue(results, mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
				for (int i=0; i<json.size(); ++i) {
					Map<String, Object> jsonObject = json.get(i);
					String key = jsonObject.get("fieldName").toString();
					if (!aggregatedResults.contains(key)) {
						aggregatedResults.add(jsonObject);
					}
				}
			} catch (IOException e) {
				logger.warn("failed to read response json " + e.getMessage());
			}
    	}
    	return aggregatedResults;
    }
    
    private Set<String> getLocalFieldNamesFromIndexingSerivce() {
    	URI uri = buildUri(indexingServiceUrl, indexingServicePort, getItemFieldsPath, null);
        logger.info("sending a request to " + uri.toString() + " in order to clean non existing field names");
        
        Response response = httpClient.target(uri.toString()).request().get();
        if (response.getStatus() >= 400) { // we had an issue, we can't modify the body without any response
       	 logger.warn("get error when calling GET '/item/fields' in indexing service");
       	 return new HashSet<String>();
        }
        
        String data = response.readEntity(String.class);
        Set<String> localFieldNames = new HashSet<String>();
		try {
			List<Map<String, Object>> json = mapper.readValue(data, mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
			for (int i=0; i<json.size(); ++i) {
				Map<String, Object> jsonObject = json.get(i);
				String fieldName = jsonObject.get("fieldName").toString();
				localFieldNames.add(fieldName);
			}
		}
		catch (Exception ex) {
			logger.warn("get error while processing GET '/item/fields' response from indexing service...");
		}
		return localFieldNames;
    }
    
	private boolean doesFqListContainFieldNameNotFromLocal(Map<String, Object> body, Set<String> localFieldNames) {
    	if (body.get("fq") == null) {
			return false; 
		}
    	try {
    		String fqStr = body.get("fq").toString();
    		fqStr = fqStr.substring(1, fqStr.length()-1).trim();
    		String[] fqList = fqStr.split(",");
    		for (String fq : fqList) {
    			fq = fq.trim();
    			String fqFieldName = fq.split(":")[0];
    			logger.info("checking fq: " + fq + ", fq fieldName = " + fqFieldName);
    			if (fqFieldName != null && !localFieldNames.contains(fqFieldName)) {
    				return true;
    			}
    		}
    	}
    	catch (Exception ex) {
    		logger.warn("error while trying to cast fq field to json");
    	}
    	return false;
    }
    
    // remove from body.facet.fields all field names that doesn't exist in the local indexing service instance
    private void removeNonExistingFieldNamesFromBody(Map<String, Object> body, Set<String> localFieldNames) {
    	if (body.get("facet") == null) {
    		return;
    	}
    	JsonObject facetJsonObject = jsonParser.parse(body.get("facet").toString()).getAsJsonObject();
    	JsonArray fieldJsonObject = facetJsonObject.get("field").getAsJsonArray();
    	JsonArray jsonElementsToRemove = new JsonArray();
    	for (JsonElement element : fieldJsonObject) {
    		String fieldName = element.getAsString();
    		if (fieldName != null && !localFieldNames.contains(fieldName)) {
    			jsonElementsToRemove.add(element);
    		}
    	}
    	for (JsonElement element : jsonElementsToRemove) {
    		logger.info("fieldName "+ element.toString() + " doesn't exist in local instance, removing it...");
    		fieldJsonObject.remove(element);
    	}
    	body.put("facet", facetJsonObject.toString());
    }
    
    /***********************************   indexing-service extra logic - END   ***********************************/
    
    
    /***********************************   Http Requests   ***********************************/
    
    private URI buildUri(String host, int port, String path, HashMap<String, List<String>> queryParams) {
    	// Prepare the destination URL for the request
        UriBuilder uriBuilder = UriBuilder.fromUri("");
        uriBuilder.scheme("http");
        if (queryParams != null) {
        	for (Entry<String, List<String>> queryParam : queryParams.entrySet()) {
        		if (queryParam.getValue() != null && !queryParam.getValue().isEmpty()) {
        			uriBuilder.queryParam(queryParam.getKey(), queryParam.getValue());
        		}
        	}
        }
        return uriBuilder.host(host).port(port).path(path).build();
    }
    
    // forward post request
    private Response forwardPostRequest(String from, String to, Map<String, Object> body, MultivaluedMap<String, Object> headers) {
    	logger.info("got a request to endpoint " + from + ", forwarding it to " + to + " with body: " + body.toString());
        
        Response response = httpClient.target(to).request().headers(headers).post(Entity.json(body));
        if (response.getStatus() >= 200 && response.getStatus() <= 300) {
        	String data = response.readEntity(String.class);
            return Response.status(Status.OK)
            				.entity(data)
            				.type(MediaType.APPLICATION_JSON)
            				.header("frontendServiceUrl", frontendServiceUrl)
            				.build();
        }
        else {
        	return response;
        }
    }
    
    // Sends the get request to all the Delegate services which are registered in the Eureka server
    private HashMap<ServiceEndpoint, String> sendGetRequestToAllServices(String urlPath, HashMap<String, List<String>> queryParams) {
    	logger.info("send get requests to all delegates");
    	List<ServiceEndpoint> endpointList = getEndpointsFromEureka();
        List<Future<Response>> futureList = new ArrayList<Future<Response>>();

        for (ServiceEndpoint endpoint : endpointList) {
            // Prepare the destination URL
            UriBuilder uriBuilder = UriBuilder.fromUri("");
            uriBuilder.scheme("http");
            // add all query params to the request
            for (Entry<String, List<String>> queryParam : queryParams.entrySet()) {
            	for (String paramValue : queryParam.getValue()) {
            		uriBuilder.queryParam(queryParam.getKey(), paramValue);
            	}
            }
            URI uri = uriBuilder.host(endpoint.getHostName()).port(endpoint.getPort()).path(urlPath).build();
            
            logger.info("sending the request to " + endpoint.toString() + "...");
            Future<Response> result = httpClient.target(uri.toString()).request().async().get();
            futureList.add(result);
        }
        return getResponseListFromAllDelegates(endpointList, futureList);
    }
    
    // Sends the post request to all the Delegate services which are registered in the Eureka server
    private HashMap<ServiceEndpoint, String> sendPostRequestToAllServices(List<ServiceEndpoint> endpointList, String urlPath, Map<String, Object> body) {
    	logger.info("send post requests to all delegates");
        List<Future<Response>> futureList = new ArrayList<Future<Response>>();

        for (ServiceEndpoint endpoint : endpointList) {
            URI uri = buildUri(endpoint.getHostName(), endpoint.getPort(), urlPath, null);
            logger.info("sending the request to " + endpoint.toString() + "...");
            Future<Response> result = httpClient.target(uri.toString()).request().async().post(Entity.json(body));
            futureList.add(result);
        }
        return getResponseListFromAllDelegates(endpointList, futureList);
    }
    
    // get responses from all Delegate services which are registered in the Eureka server
    private HashMap<ServiceEndpoint, String> getResponseListFromAllDelegates(List<ServiceEndpoint> endpointList, List<Future<Response>> futureList) {
        // Wait (one by one) for the responses from all the services
        HashMap<ServiceEndpoint, String> resList = new HashMap<ServiceEndpoint, String>();
        for(int i = 0; i< futureList.size(); i++) {
            Future<Response> response = futureList.get(i);
            ServiceEndpoint endpoint = endpointList.get(i);
            logger.info("got response from " + endpoint.toString());
            try {
            	Response res = response.get(REQ_TIMEOUT_SEC, TimeUnit.SECONDS);
                String data = res.readEntity(String.class);
                endpoint.setFrontendServiceUrl(res.getHeaderString("frontendServiceUrl"));
                resList.put(endpoint, data);
            } catch(Exception e) {
                logger.warn("Failed to send post request to eureka endpoint: id: " +  endpoint.getId() +
                			" appName:" + endpoint.getAppName() +
                            " (" + endpoint.getHostName() +
                            ":" + endpoint.getPort() + ") - " +
                            e.getMessage());
            }
        }
        logger.info("aggregated results: \n" + resList.toString());
        return resList;
    }
    
    /***********************************   Http Requests - END   ***********************************/
    
    
    /***********************************   Eureka   ***********************************/
    
    @GET
    @Path("eureka")
    @Produces({ MediaType.APPLICATION_JSON })
    // Return the Delegate services registered in Eureka server (Used for debug)
    public Response eureka() {
        List<ServiceEndpoint> endpointList = getEndpointsFromEureka();
        return Response.status(Response.Status.OK).entity(endpointList).build();
    }
    
    // Initializes Eureka client and registers the service with the Eureka server
    private boolean initEureka() {
        try {
        	logger.info("trying to init eureka client");
        	MyDataCenterInstanceConfig instanceConfig = new MyDataCenterInstanceConfig();
        	InstanceInfo instanceInfo = new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get();
        	applicationInfoManager = new ApplicationInfoManager(instanceConfig, instanceInfo);
        	eurekaClient = new DiscoveryClient(applicationInfoManager, new DefaultEurekaClientConfig());
        	applicationInfoManager.setInstanceStatus(InstanceInfo.InstanceStatus.UP);
        	logger.info("Delegate has been registered in Eureka: " +
        				instanceInfo.getAppName() + "/" +
        				instanceInfo.getVIPAddress() + "(" +
        				instanceInfo.getId() +") " +
        				instanceInfo.getHostName() + ":" +
        				instanceInfo.getPort());
        } catch (Exception e) {
        	logger.error(e.getMessage());
        	e.printStackTrace();
            return false;
        }
        return true;
    }
    
    // Returns a list of Delegate services registered in Eureka server
    private List<ServiceEndpoint> getEndpointsFromEureka() {
        List<ServiceEndpoint> delegateList = new ArrayList<ServiceEndpoint>();
        List<InstanceInfo> instanceList = eurekaClient.getInstancesByVipAddress(vipAddress, false);
        for (InstanceInfo info : instanceList) {
           // Filter out services that are not UP
           if (info.getStatus() == InstanceInfo.InstanceStatus.UP) {
               delegateList.add(new ServiceEndpoint(info.getId(), info.getHostName(), info.getPort(), info.getAppName()));
           }
        }
        return delegateList;
    }
    
    /***********************************   Eureka - END   ***********************************/
}
