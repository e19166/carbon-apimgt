package org.wso2.carbon.apimgt.governance.rest.api;

import org.wso2.carbon.apimgt.governance.rest.api.dto.GovernanceSettingsDTO;
import org.wso2.carbon.apimgt.governance.rest.api.SettingsApiService;
import org.wso2.carbon.apimgt.governance.rest.api.impl.SettingsApiServiceImpl;
import org.wso2.carbon.apimgt.governance.api.error.APIMGovernanceException;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.inject.Inject;

import io.swagger.annotations.*;
import java.io.InputStream;

import org.apache.cxf.jaxrs.ext.MessageContext;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.jaxrs.ext.multipart.Multipart;

import java.util.Map;
import java.util.List;
import javax.validation.constraints.*;
@Path("/settings")

@Api(description = "the settings API")




public class SettingsApi  {

  @Context MessageContext securityContext;

SettingsApiService delegate = new SettingsApiServiceImpl();


    @GET
    
    
    @Produces({ "application/json" })
    @ApiOperation(value = "Get governance settings", notes = "Retrieve governance settings, including which optional capabilities are available on this deployment. ", response = GovernanceSettingsDTO.class, authorizations = {
        @Authorization(value = "OAuth2Security", scopes = {
            @AuthorizationScope(scope = "apim:gov_rule_read", description = "Read governance rulesets")
        })
    }, tags={ "Settings" })
    @ApiResponses(value = { 
        @ApiResponse(code = 200, message = "OK. Governance settings returned. ", response = GovernanceSettingsDTO.class),
        @ApiResponse(code = 401, message = "", response = Void.class),
        @ApiResponse(code = 500, message = "", response = Void.class) })
    public Response getGovernanceSettings() throws APIMGovernanceException{
        return delegate.getGovernanceSettings(securityContext);
    }
}
