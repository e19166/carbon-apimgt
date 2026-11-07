/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.gateway.mediators;

import org.apache.synapse.SynapseConstants;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;
import org.wso2.carbon.apimgt.api.APIConstants.AIAPIConstants;
import org.wso2.carbon.apimgt.api.LLMProviderConfiguration;
import org.wso2.carbon.apimgt.api.LLMProviderMetadata;
import org.wso2.carbon.apimgt.api.LLMProviderService;
import org.wso2.carbon.apimgt.api.LLMResponseMetaData;
import org.wso2.carbon.apimgt.api.gateway.FailoverPolicyConfigDTO;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;
import org.wso2.carbon.apimgt.api.model.LLMProviderInfo;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.handlers.analytics.Constants;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests the token usage analytics of {@link AIAPIMediator}: applying a provider's default response metadata on top of
 * its stored rules, extracting the token usage counters, and publishing them through
 * PRODUCT_ANALYTICS_PROPS_TO_PUBLISH.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({APIUtil.class, RelayUtils.class, JsonUtil.class, AIAPIMediator.class})
public class AIAPIMediatorTest {

    private Map<String, Object> properties;
    // Backs messageContext.getPropertyKeySet(), mirroring Synapse's own key-set-based property removal.
    private Set<String> propertyKeySet;
    private Axis2MessageContext messageContext;
    private org.apache.axis2.context.MessageContext axis2MessageContext;
    private LLMProviderService llmProviderService;
    // Values in the simulated LLM response, by attribute name.
    private Map<String, String> responseValues;
    // Rules the mediator passed to the provider service for extraction.
    private List<LLMProviderMetadata> extractionRules;

    @Before
    public void setUp() throws Exception {

        PowerMockito.mockStatic(APIUtil.class);
        PowerMockito.mockStatic(RelayUtils.class);
        PowerMockito.mockStatic(JsonUtil.class);

        properties = new HashMap<>();
        propertyKeySet = new HashSet<>();
        messageContext = Mockito.mock(Axis2MessageContext.class);
        axis2MessageContext = Mockito.mock(org.apache.axis2.context.MessageContext.class);
        Mockito.when(messageContext.getAxis2MessageContext()).thenReturn(axis2MessageContext);
        Mockito.when(messageContext.getProperty(Mockito.anyString()))
                .thenAnswer(invocation -> properties.get((String) invocation.getArguments()[0]));
        Mockito.doAnswer(invocation -> {
            properties.put((String) invocation.getArguments()[0], invocation.getArguments()[1]);
            propertyKeySet.add((String) invocation.getArguments()[0]);
            return null;
        }).when(messageContext).setProperty(Mockito.anyString(), Mockito.any());
        Mockito.when(messageContext.getPropertyKeySet()).thenReturn(propertyKeySet);

        responseValues = new HashMap<>();
        llmProviderService = Mockito.mock(LLMProviderService.class);
        Mockito.when(llmProviderService.getDefaultResponseMetadata()).thenReturn(Arrays.asList(
                tokenUsageRule("cacheReadInputTokens"), tokenUsageRule("reasoningTokens")));
        // Extracts each attribute the given rules name and the simulated response has.
        Mockito.when(llmProviderService.getResponseMetadata(Mockito.any(LLMResponseMetaData.class), Mockito.anyList(),
                Mockito.anyMap())).thenAnswer(invocation -> {
            List<LLMProviderMetadata> rules = invocation.getArgument(1);
            Map<String, String> metadataMap = invocation.getArgument(2);
            extractionRules = new ArrayList<>(rules);
            for (LLMProviderMetadata rule : rules) {
                if (responseValues.containsKey(rule.getAttributeName())) {
                    metadataMap.put(rule.getAttributeName(), responseValues.get(rule.getAttributeName()));
                }
            }
            return metadataMap;
        });
    }

    @Test
    public void testWithDefaultResponseMetadataAddsDefaultsMissingFromStoredRules() {

        List<LLMProviderMetadata> stored = Arrays.asList(coreRule("promptTokenCount"), coreRule("totalTokenCount"));
        List<LLMProviderMetadata> defaults = Arrays.asList(tokenUsageRule("cacheReadInputTokens"),
                tokenUsageRule("reasoningTokens"));

        List<LLMProviderMetadata> rules = AIAPIMediator.withDefaultResponseMetadata(stored, defaults);

        Assert.assertEquals(Arrays.asList("promptTokenCount", "totalTokenCount", "cacheReadInputTokens",
                "reasoningTokens"), names(rules));
    }

    @Test
    public void testWithDefaultResponseMetadataKeepsStoredRuleOverDefault() {

        LLMProviderMetadata customised = new LLMProviderMetadata("cacheReadInputTokens",
                AIAPIConstants.INPUT_SOURCE_PAYLOAD, "$.usage.custom_cache", false);
        LLMProviderMetadata blank = new LLMProviderMetadata("reasoningTokens", AIAPIConstants.INPUT_SOURCE_PAYLOAD,
                "", false);
        List<LLMProviderMetadata> defaults = Arrays.asList(tokenUsageRule("cacheReadInputTokens"),
                tokenUsageRule("reasoningTokens"));

        List<LLMProviderMetadata> rules = AIAPIMediator.withDefaultResponseMetadata(Arrays.asList(customised, blank),
                defaults);

        Assert.assertEquals(2, rules.size());
        Assert.assertSame(customised, rules.get(0));
        Assert.assertSame(blank, rules.get(1));
    }

    @Test
    public void testWithDefaultResponseMetadataDoesNotChangeItsInputs() {

        List<LLMProviderMetadata> stored = Collections.unmodifiableList(
                Collections.singletonList(coreRule("promptTokenCount")));
        List<LLMProviderMetadata> defaults = Collections.unmodifiableList(
                Collections.singletonList(tokenUsageRule("cacheReadInputTokens")));

        List<LLMProviderMetadata> rules = AIAPIMediator.withDefaultResponseMetadata(stored, defaults);

        Assert.assertEquals(Arrays.asList("promptTokenCount", "cacheReadInputTokens"), names(rules));
        Assert.assertEquals(1, stored.size());
        Assert.assertEquals(1, defaults.size());
    }

    @Test
    public void testWithDefaultResponseMetadataReturnsCopyWhenThereAreNoDefaults() {

        List<LLMProviderMetadata> stored = Collections.singletonList(coreRule("promptTokenCount"));

        for (List<LLMProviderMetadata> defaults : Arrays.<List<LLMProviderMetadata>>asList(null,
                Collections.emptyList())) {
            List<LLMProviderMetadata> rules = AIAPIMediator.withDefaultResponseMetadata(stored, defaults);

            Assert.assertEquals(stored, rules);
            Assert.assertNotSame(stored, rules);
        }
    }

    @Test
    public void testWithDefaultResponseMetadataWithoutStoredRules() {

        List<LLMProviderMetadata> rules = AIAPIMediator.withDefaultResponseMetadata(null,
                Collections.singletonList(tokenUsageRule("cacheReadInputTokens")));

        Assert.assertEquals(Collections.singletonList("cacheReadInputTokens"), names(rules));
    }

    @Test
    public void testWithDefaultResponseMetadataSkipsNullAndDuplicateDefaults() {

        List<LLMProviderMetadata> stored = new ArrayList<>(Arrays.asList(null, coreRule("promptTokenCount"),
                coreRule(null)));
        List<LLMProviderMetadata> defaults = Arrays.asList(null, tokenUsageRule(null),
                tokenUsageRule("cacheReadInputTokens"), tokenUsageRule("cacheReadInputTokens"));

        List<LLMProviderMetadata> rules = AIAPIMediator.withDefaultResponseMetadata(stored, defaults);

        // Stored entries are kept as they are; only the first default with a given name is added.
        Assert.assertEquals(4, rules.size());
        Assert.assertEquals("cacheReadInputTokens", rules.get(3).getAttributeName());
    }

    @Test
    public void testExtractTokenUsageCountersIncludesOnlyTokenUsageRules() {

        Map<String, String> metadataMap = new HashMap<>();
        metadataMap.put("promptTokenCount", "1200");
        metadataMap.put("cacheReadInputTokens", "800");
        metadataMap.put("reasoningTokens", "120");
        List<LLMProviderMetadata> rules = Arrays.asList(coreRule("promptTokenCount"),
                tokenUsageRule("reasoningTokens"), tokenUsageRule("cacheReadInputTokens"));

        Map<String, Object> counters = AIAPIMediator.extractTokenUsageCounters(metadataMap, rules);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("reasoningTokens", 120);
        expected.put("cacheReadInputTokens", 800);
        Assert.assertEquals(expected, counters);
        Assert.assertEquals(Arrays.asList("reasoningTokens", "cacheReadInputTokens"),
                new ArrayList<>(counters.keySet()));
    }

    @Test
    public void testExtractTokenUsageCountersKeepsZeroAndSkipsMissingCounters() {

        Map<String, String> metadataMap = Collections.singletonMap("cacheReadInputTokens", " 0 ");
        List<LLMProviderMetadata> rules = Arrays.asList(tokenUsageRule("cacheReadInputTokens"),
                tokenUsageRule("cacheWriteInputTokens"));

        Map<String, Object> counters = AIAPIMediator.extractTokenUsageCounters(metadataMap, rules);

        Assert.assertEquals(Collections.singletonMap("cacheReadInputTokens", 0), counters);
    }

    @Test
    public void testExtractTokenUsageCountersSkipsValuesThatAreNotWholeNumbers() {

        Map<String, String> metadataMap = new HashMap<>();
        metadataMap.put("objectValue", "{\"ephemeral_5m_input_tokens\":10}");
        metadataMap.put("decimalValue", "800.0");
        metadataMap.put("textValue", "null");
        metadataMap.put("tooLarge", "99999999999");
        metadataMap.put("emptyValue", "");
        metadataMap.put("cacheReadInputTokens", "800");
        List<LLMProviderMetadata> rules = new ArrayList<>();
        for (String name : metadataMap.keySet()) {
            rules.add(tokenUsageRule(name));
        }

        Map<String, Object> counters = AIAPIMediator.extractTokenUsageCounters(metadataMap, rules);

        Assert.assertEquals(Collections.singletonMap("cacheReadInputTokens", 800), counters);
    }

    @Test
    public void testExtractTokenUsageCountersWithMissingInputs() {

        Map<String, String> metadataMap = Collections.singletonMap("cacheReadInputTokens", "800");

        Assert.assertTrue(AIAPIMediator.extractTokenUsageCounters(null,
                Collections.singletonList(tokenUsageRule("cacheReadInputTokens"))).isEmpty());
        Assert.assertTrue(AIAPIMediator.extractTokenUsageCounters(metadataMap, null).isEmpty());
        Assert.assertTrue(AIAPIMediator.extractTokenUsageCounters(metadataMap,
                Arrays.asList(null, tokenUsageRule(null))).isEmpty());
    }

    @Test
    public void testProcessOutboundResponsePublishesTokenUsageCountersWhenAnalyticsEnabled() throws Exception {

        PowerMockito.when(APIUtil.isAnalyticsEnabled()).thenReturn(true);
        responseValues.put("promptTokenCount", "1200");
        responseValues.put("cacheReadInputTokens", "800");
        responseValues.put("reasoningTokens", "120");

        Map<String, String> metadataMap = processOutboundResponse(coreRule("promptTokenCount"));

        Assert.assertEquals(Arrays.asList("promptTokenCount", "cacheReadInputTokens", "reasoningTokens"),
                names(extractionRules));
        Assert.assertSame(metadataMap, properties.get(AIAPIConstants.AI_API_RESPONSE_METADATA));
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("cacheReadInputTokens", 800);
        counters.put("reasoningTokens", 120);
        Assert.assertEquals(Collections.singletonMap(Constants.AI_TOKEN_USAGE, counters),
                properties.get(APIMgtGatewayConstants.PRODUCT_ANALYTICS_PROPS));
    }

    @Test
    public void testProcessOutboundResponseUsesOnlyStoredRulesWhenAnalyticsDisabled() throws Exception {

        PowerMockito.when(APIUtil.isAnalyticsEnabled()).thenReturn(false);
        responseValues.put("promptTokenCount", "1200");
        responseValues.put("cacheReadInputTokens", "800");

        Map<String, String> metadataMap = processOutboundResponse(coreRule("promptTokenCount"));

        Assert.assertEquals(Collections.singletonList("promptTokenCount"), names(extractionRules));
        Assert.assertEquals(Collections.singletonMap("promptTokenCount", "1200"), metadataMap);
        Assert.assertFalse(properties.containsKey(APIMgtGatewayConstants.PRODUCT_ANALYTICS_PROPS));
        Mockito.verify(llmProviderService, Mockito.never()).getDefaultResponseMetadata();
    }

    @Test
    public void testProcessOutboundResponseKeepsStoredRuleOverDefault() throws Exception {

        PowerMockito.when(APIUtil.isAnalyticsEnabled()).thenReturn(true);
        responseValues.put("cacheReadInputTokens", "800");
        responseValues.put("reasoningTokens", "120");
        // A stored rule without the TOKEN_USAGE category, which the default with the same name must not replace.
        LLMProviderMetadata storedReasoningRule = new LLMProviderMetadata("reasoningTokens",
                AIAPIConstants.INPUT_SOURCE_PAYLOAD, "$.usage.custom_reasoning", false);

        processOutboundResponse(storedReasoningRule);

        Assert.assertEquals(Arrays.asList("reasoningTokens", "cacheReadInputTokens"), names(extractionRules));
        Assert.assertSame(storedReasoningRule, extractionRules.get(0));
        Assert.assertEquals(Collections.singletonMap(Constants.AI_TOKEN_USAGE,
                        Collections.singletonMap("cacheReadInputTokens", 800)),
                properties.get(APIMgtGatewayConstants.PRODUCT_ANALYTICS_PROPS));
    }

    @Test
    public void testProcessOutboundResponseReplacesCountersOfEarlierAttempt() throws Exception {

        PowerMockito.when(APIUtil.isAnalyticsEnabled()).thenReturn(true);
        Map<String, Object> earlierCounters = new LinkedHashMap<>();
        earlierCounters.put("cacheReadInputTokens", 5);
        earlierCounters.put("reasoningTokens", 9);
        Map<String, Object> otherSection = Collections.singletonMap("requests", 1);
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("otherFeatureUsage", otherSection);
        sections.put(Constants.AI_TOKEN_USAGE, earlierCounters);
        properties.put(APIMgtGatewayConstants.PRODUCT_ANALYTICS_PROPS, sections);
        responseValues.put("cacheReadInputTokens", "800");

        processOutboundResponse(coreRule("promptTokenCount"));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("otherFeatureUsage", otherSection);
        expected.put(Constants.AI_TOKEN_USAGE, Collections.singletonMap("cacheReadInputTokens", 800));
        Assert.assertEquals(expected, properties.get(APIMgtGatewayConstants.PRODUCT_ANALYTICS_PROPS));
    }

    @Test
    public void testProcessOutboundResponseWithoutCountersClearsCountersOfEarlierAttempt() throws Exception {

        PowerMockito.when(APIUtil.isAnalyticsEnabled()).thenReturn(true);
        properties.put(APIMgtGatewayConstants.PRODUCT_ANALYTICS_PROPS, Collections.singletonMap(
                Constants.AI_TOKEN_USAGE, Collections.singletonMap("cacheReadInputTokens", 800)));
        responseValues.put("promptTokenCount", "1200");

        processOutboundResponse(coreRule("promptTokenCount"));

        Assert.assertEquals(Collections.singletonMap(Constants.AI_TOKEN_USAGE, Collections.emptyMap()),
                properties.get(APIMgtGatewayConstants.PRODUCT_ANALYTICS_PROPS));
    }

    @Test
    public void testProcessOutboundResponseDoesNotTouchListenerAnalyticsProps() throws Exception {

        PowerMockito.when(APIUtil.isAnalyticsEnabled()).thenReturn(true);
        Map<String, Object> listenerProps = new LinkedHashMap<>();
        listenerProps.put("salesRegion", "EMEA");
        properties.put(APIMgtGatewayConstants.ADDITIONAL_ANALYTICS_PROPS, listenerProps);
        responseValues.put("cacheReadInputTokens", "800");

        processOutboundResponse(coreRule("promptTokenCount"));

        Assert.assertSame(listenerProps, properties.get(APIMgtGatewayConstants.ADDITIONAL_ANALYTICS_PROPS));
        Assert.assertEquals(Collections.singletonMap("salesRegion", "EMEA"), listenerProps);
    }

    /**
     * Runs the mediator's outbound response processing for a provider with the given stored rules and no failover or
     * round robin configuration.
     */
    private Map<String, String> processOutboundResponse(LLMProviderMetadata... storedRules) throws Exception {

        LLMProviderConfiguration configuration = new LLMProviderConfiguration();
        configuration.setConnectorType("openAi_2.0.0");
        configuration.setMetadata(Arrays.asList(storedRules));
        LLMProviderInfo provider = new LLMProviderInfo();
        provider.setName("OpenAI");
        provider.setApiVersion("2.0.0");
        provider.setConfigurations(configuration);
        Map<String, String> metadataMap = new HashMap<>();

        Whitebox.invokeMethod(new AIAPIMediator(), "processOutboundResponse", messageContext, configuration,
                llmProviderService, metadataMap, provider);
        return metadataMap;
    }

    private static LLMProviderMetadata coreRule(String name) {

        return new LLMProviderMetadata(name, AIAPIConstants.INPUT_SOURCE_PAYLOAD, "$.usage." + name, true);
    }

    private static LLMProviderMetadata tokenUsageRule(String name) {

        return new LLMProviderMetadata(name, AIAPIConstants.INPUT_SOURCE_PAYLOAD, "$.usage." + name, false,
                AIAPIConstants.ANALYTICS_CATEGORY_TOKEN_USAGE);
    }

    private static List<String> names(List<LLMProviderMetadata> rules) {

        List<String> names = new ArrayList<>();
        for (LLMProviderMetadata rule : rules) {
            names.add(rule.getAttributeName());
        }
        return names;
    }

    // --- Fault-path failover (timeouts / connection errors) ---

    @Test
    public void testClearFaultPropertiesRemovesOnlyTheErrorMarkers() throws Exception {

        properties.put(SynapseConstants.ERROR_CODE, 101504);
        properties.put(SynapseConstants.BASE_ERROR_CODE, 101504);
        properties.put(SynapseConstants.ERROR_MESSAGE, "Connection timed out");
        properties.put(SynapseConstants.ERROR_DETAIL, "stack trace");
        properties.put(SynapseConstants.ERROR_EXCEPTION, new RuntimeException("boom"));
        properties.put(SynapseConstants.PROTOCOL_STATE_ON_FAILURE, "REQUEST_DONE");
        properties.put(SynapseConstants.SENDING_FAULT, Boolean.TRUE);
        properties.put(AIAPIConstants.AI_API_REQUEST_METADATA, new HashMap<>());
        propertyKeySet.addAll(properties.keySet());

        Whitebox.invokeMethod(new AIAPIMediator(), "clearFaultProperties", messageContext);

        Assert.assertFalse(propertyKeySet.contains(SynapseConstants.ERROR_CODE));
        Assert.assertFalse(propertyKeySet.contains(SynapseConstants.BASE_ERROR_CODE));
        Assert.assertFalse(propertyKeySet.contains(SynapseConstants.ERROR_MESSAGE));
        Assert.assertFalse(propertyKeySet.contains(SynapseConstants.ERROR_DETAIL));
        Assert.assertFalse(propertyKeySet.contains(SynapseConstants.ERROR_EXCEPTION));
        Assert.assertFalse(propertyKeySet.contains(SynapseConstants.PROTOCOL_STATE_ON_FAILURE));
        Assert.assertFalse(propertyKeySet.contains(SynapseConstants.SENDING_FAULT));
        // A property unrelated to the fault must survive.
        Assert.assertTrue(propertyKeySet.contains(AIAPIConstants.AI_API_REQUEST_METADATA));
    }

    @Test
    public void testProcessFaultFlowDelegatesWhenThereIsNoFailoverPolicy() throws Exception {

        properties.put(SynapseConstants.ERROR_CODE, 101504);
        // No FAILOVER_CONFIGS property at all: no Model Failover policy applies to this request's model.

        Whitebox.invokeMethod(new AIAPIMediator(), "processFaultFlow", messageContext,
                new LLMProviderConfiguration(), new LLMProviderInfo());

        Assert.assertEquals(AIAPIConstants.FAULT_FAILOVER_ACTION_DELEGATE,
                properties.get(AIAPIConstants.FAULT_FAILOVER_ACTION));
    }

    @Test
    public void testProcessFaultFlowRetriesAndReusesHandleFailoverWhenAPolicyApplies() throws Exception {

        properties.put(SynapseConstants.ERROR_CODE, 101504);
        properties.put(SynapseConstants.ERROR_MESSAGE, "Request timed out"); // must be cleared on a successful retry
        Map<String, Object> failoverConfigs = new HashMap<>();
        properties.put(AIAPIConstants.FAILOVER_CONFIGS, failoverConfigs);
        propertyKeySet.addAll(properties.keySet());

        AIAPIMediator mediator = PowerMockito.spy(new AIAPIMediator());
        LLMProviderConfiguration providerConfiguration = new LLMProviderConfiguration();
        LLMProviderInfo provider = new LLMProviderInfo();
        // handleFailover is tested elsewhere; here we just check it's called with the right arguments.
        PowerMockito.doNothing().when(mediator, "handleFailover", Mockito.eq(messageContext),
                Mockito.eq(providerConfiguration), Mockito.eq(failoverConfigs), Mockito.anyBoolean(),
                Mockito.eq(true));

        Whitebox.invokeMethod(mediator, "processFaultFlow", messageContext, providerConfiguration, provider);

        PowerMockito.verifyPrivate(mediator).invoke("handleFailover", messageContext, providerConfiguration,
                failoverConfigs, true, true);
        Assert.assertEquals(AIAPIConstants.FAULT_FAILOVER_ACTION_RETRY,
                properties.get(AIAPIConstants.FAULT_FAILOVER_ACTION));
        Assert.assertFalse("stale error markers must be cleared before the retry",
                propertyKeySet.contains(SynapseConstants.ERROR_MESSAGE));
    }

    @Test
    public void testProcessFaultFlowDelegatesWhenHandleFailoverExhaustsFallbacks() throws Exception {

        properties.put(SynapseConstants.ERROR_CODE, 101504);
        properties.put(SynapseConstants.ERROR_MESSAGE, "Request timed out"); // must survive for the API's own
                                                                              // fault sequence
        Map<String, Object> failoverConfigs = new HashMap<>();
        properties.put(AIAPIConstants.FAILOVER_CONFIGS, failoverConfigs);
        propertyKeySet.addAll(properties.keySet());

        AIAPIMediator mediator = PowerMockito.spy(new AIAPIMediator());
        LLMProviderConfiguration providerConfiguration = new LLMProviderConfiguration();
        LLMProviderInfo provider = new LLMProviderInfo();
        // handleFailover exhausted the fallbacks: it rejects instead of retrying, as it does today.
        PowerMockito.doAnswer(invocation -> {
            messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, AIAPIConstants.REJECT_ENDPOINT);
            return null;
        }).when(mediator, "handleFailover", Mockito.eq(messageContext),
                Mockito.eq(providerConfiguration), Mockito.eq(failoverConfigs), Mockito.anyBoolean(),
                Mockito.eq(true));

        Whitebox.invokeMethod(mediator, "processFaultFlow", messageContext, providerConfiguration, provider);

        Assert.assertEquals("an exhausted retry must delegate, not retry into a dead end",
                AIAPIConstants.FAULT_FAILOVER_ACTION_DELEGATE, properties.get(AIAPIConstants.FAULT_FAILOVER_ACTION));
        Assert.assertTrue("the original error markers must survive for the API's own fault sequence",
                propertyKeySet.contains(SynapseConstants.ERROR_MESSAGE));
    }

    @Test
    public void testPreserveFailoverPropertiesSavesTimeoutForLaterRestore() throws Exception {

        PowerMockito.when(JsonUtil.jsonPayloadToString(axis2MessageContext)).thenReturn("{\"model\":\"gpt-4o\"}");
        FailoverPolicyConfigDTO policyConfig = new FailoverPolicyConfigDTO();
        policyConfig.setRequestTimeout(10L); // seconds
        policyConfig.setSuspendDuration(30L);
        ModelEndpointDTO target = new ModelEndpointDTO();
        target.setEndpointId("sweden");
        target.setModel("gpt-4o");

        Whitebox.invokeMethod(new AIAPIMediator(), "preserveFailoverPropertiesInMsgCtx", messageContext,
                policyConfig, target, new ArrayList<ModelEndpointDTO>());

        @SuppressWarnings("unchecked")
        Map<String, Object> failoverConfigs =
                (Map<String, Object>) properties.get(AIAPIConstants.FAILOVER_CONFIGS);
        Assert.assertNotNull(failoverConfigs);
        Assert.assertEquals(10000L, failoverConfigs.get(AIAPIConstants.FAILOVER_REQUEST_TIMEOUT));
        Assert.assertEquals(10000L, properties.get(AIAPIConstants.REQUEST_TIMEOUT));
    }

    @Test
    public void testPreserveFailoverPropertiesSavesACopyOfTheHeaders() throws Exception {

        PowerMockito.when(JsonUtil.jsonPayloadToString(axis2MessageContext)).thenReturn("{\"model\":\"gpt-4o\"}");
        Map<String, String> liveHeaders = new HashMap<>();
        liveHeaders.put("Authorization", "sweden-secret");
        PowerMockito.when(axis2MessageContext.getProperty(
                org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS)).thenReturn(liveHeaders);

        FailoverPolicyConfigDTO policyConfig = new FailoverPolicyConfigDTO();
        ModelEndpointDTO target = new ModelEndpointDTO();
        target.setEndpointId("sweden");
        target.setModel("gpt-4o");

        Whitebox.invokeMethod(new AIAPIMediator(), "preserveFailoverPropertiesInMsgCtx", messageContext,
                policyConfig, target, new ArrayList<ModelEndpointDTO>());

        @SuppressWarnings("unchecked")
        Map<String, Object> failoverConfigs =
                (Map<String, Object>) properties.get(AIAPIConstants.FAILOVER_CONFIGS);
        Object savedHeaders = failoverConfigs.get(AIAPIConstants.REQUEST_HEADERS);
        Assert.assertNotSame("the saved headers must be a copy, not the live map", liveHeaders, savedHeaders);
        Assert.assertEquals("sweden-secret", ((Map<?, ?>) savedHeaders).get("Authorization"));

        // Mutating the live map afterwards (as an endpoint's auth mediator would) must not affect the saved copy.
        liveHeaders.put("Authorization", "spain-secret");
        Assert.assertEquals("sweden-secret", ((Map<?, ?>) savedHeaders).get("Authorization"));
    }

    @Test
    public void testApplyFailoverRequestTimeoutRestoresTheSavedValue() throws Exception {

        properties.put(AIAPIConstants.REQUEST_TIMEOUT, 60000L); // the global default, set earlier by mediate()
        Map<String, Object> failoverConfigs = new HashMap<>();
        failoverConfigs.put(AIAPIConstants.FAILOVER_REQUEST_TIMEOUT, 10000L); // the policy's own timeout

        Whitebox.invokeMethod(new AIAPIMediator(), "applyFailoverRequestTimeout", messageContext, failoverConfigs);

        Assert.assertEquals(10000L, properties.get(AIAPIConstants.REQUEST_TIMEOUT));
    }

    @Test
    public void testApplyFailoverRequestTimeoutDoesNothingWhenNoneWasSaved() throws Exception {

        properties.put(AIAPIConstants.REQUEST_TIMEOUT, 60000L);
        Map<String, Object> failoverConfigs = new HashMap<>();

        Whitebox.invokeMethod(new AIAPIMediator(), "applyFailoverRequestTimeout", messageContext, failoverConfigs);

        Assert.assertEquals(60000L, properties.get(AIAPIConstants.REQUEST_TIMEOUT));
    }

    @Test
    public void testHandleFailoverDoesNotSuspendWhenTheErrorCameBeforeTheCall() throws Exception {

        properties.put(AIAPIConstants.LLM_CALL_SENT, AIAPIConstants.LLM_CALL_NOT_SENT); // an auth mediator failed before the <call>
        ModelEndpointDTO target = new ModelEndpointDTO();
        target.setEndpointId("primary");
        target.setModel("gpt-4o");
        Map<String, Object> failoverConfigs = new HashMap<>();
        failoverConfigs.put(AIAPIConstants.FAILOVER_TARGET_MODEL_ENDPOINT, target);
        failoverConfigs.put(AIAPIConstants.SUSPEND_DURATION, 30000L);
        failoverConfigs.put(AIAPIConstants.FAILOVER_ENDPOINTS, new ArrayList<ModelEndpointDTO>()); // exhausted

        AIAPIMediator mediator = PowerMockito.spy(new AIAPIMediator());
        Whitebox.invokeMethod(mediator, "handleFailover", messageContext, new LLMProviderConfiguration(),
                failoverConfigs, true, true);

        PowerMockito.verifyPrivate(mediator, Mockito.never()).invoke("suspendTargetEndpoint",
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Assert.assertEquals(AIAPIConstants.REJECT_ENDPOINT, properties.get(AIAPIConstants.TARGET_ENDPOINT));
    }

    private AIAPIMediator exhaustedLastFallbackMediator() throws Exception {

        properties.put(AIAPIConstants.CURRENT_ENDPOINT_INDEX, 1); // the only fallback was already tried
        ModelEndpointDTO fallback = new ModelEndpointDTO();
        fallback.setEndpointId("fallback");
        fallback.setModel("gpt-4o-mini");
        Map<String, Object> failoverConfigs = new HashMap<>();
        failoverConfigs.put(AIAPIConstants.SUSPEND_DURATION, 30000L);
        failoverConfigs.put(AIAPIConstants.FAILOVER_ENDPOINTS, new ArrayList<>(Arrays.asList(fallback)));
        properties.put(AIAPIConstants.FAILOVER_CONFIGS, failoverConfigs);
        AIAPIMediator mediator = PowerMockito.spy(new AIAPIMediator());
        // Suspension needs gateway runtime state; only whether it is called matters here.
        PowerMockito.doNothing().when(mediator, "suspendTargetEndpoint", Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any());
        return mediator;
    }

    @Test
    public void testHandleFailoverSuspendsTheLastFallbackOnTheFaultPath() throws Exception {

        AIAPIMediator mediator = exhaustedLastFallbackMediator();
        Whitebox.invokeMethod(mediator, "handleFailover", messageContext, new LLMProviderConfiguration(),
                properties.get(AIAPIConstants.FAILOVER_CONFIGS), true, true);

        PowerMockito.verifyPrivate(mediator).invoke("suspendTargetEndpoint", Mockito.any(), Mockito.eq("fallback"),
                Mockito.any(), Mockito.any());
    }

    @Test
    public void testHandleFailoverDoesNotSuspendTheLastFallbackOnTheErrorReplyPath() throws Exception {

        AIAPIMediator mediator = exhaustedLastFallbackMediator();
        Whitebox.invokeMethod(mediator, "handleFailover", messageContext, new LLMProviderConfiguration(),
                properties.get(AIAPIConstants.FAILOVER_CONFIGS), true);

        PowerMockito.verifyPrivate(mediator, Mockito.never()).invoke("suspendTargetEndpoint", Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
        Assert.assertEquals(AIAPIConstants.REJECT_ENDPOINT, properties.get(AIAPIConstants.TARGET_ENDPOINT));
    }

    @Test
    public void testCopyHeadersSkipsNullValuesAndIsCaseInsensitive() throws Exception {

        Map<String, String> live = new HashMap<>();
        live.put("Authorization", "secret");
        live.put("X-Empty", null);

        Map<String, String> copy = Whitebox.invokeMethod(AIAPIMediator.class, "copyHeaders", live);

        Assert.assertEquals("secret", copy.get("authorization"));
        Assert.assertFalse("a header with no value must not become the text \"null\"",
                copy.containsKey("X-Empty"));
        Assert.assertTrue(Whitebox.<Map<String, String>>invokeMethod(AIAPIMediator.class, "copyHeaders",
                (Object) null).isEmpty());
    }

    @Test
    public void testMediateDelegatesInsteadOfStoppingWhenTheProviderIsMissingOnTheFaultPath() {

        AIAPIMediator mediator = new AIAPIMediator();
        mediator.setLlmProviderId("no-such-provider");

        mediator.setDirection(AIAPIConstants.TRAFFIC_FLOW_DIRECTION_FAULT);
        Assert.assertTrue("false would stop the fault sequence and leave the client with no reply",
                mediator.mediate(messageContext));
        Assert.assertEquals(AIAPIConstants.FAULT_FAILOVER_ACTION_DELEGATE,
                properties.get(AIAPIConstants.FAULT_FAILOVER_ACTION));

        mediator.setDirection(AIAPIConstants.TRAFFIC_FLOW_DIRECTION_IN);
        Assert.assertFalse("the IN path keeps returning false", mediator.mediate(messageContext));
    }

    @Test
    public void testProcessFaultFlowDelegatesWhenTheModelAlreadyReplied() throws Exception {

        properties.put(SynapseConstants.ERROR_CODE, 101504);
        properties.put(AIAPIConstants.LLM_CALL_SENT, AIAPIConstants.LLM_CALL_REPLIED);
        Map<String, Object> failoverConfigs = new HashMap<>();
        properties.put(AIAPIConstants.FAILOVER_CONFIGS, failoverConfigs);

        AIAPIMediator mediator = PowerMockito.spy(new AIAPIMediator());
        Whitebox.invokeMethod(mediator, "processFaultFlow", messageContext, new LLMProviderConfiguration(),
                new LLMProviderInfo());

        Assert.assertEquals("a fault after the reply must not re-send the prompt",
                AIAPIConstants.FAULT_FAILOVER_ACTION_DELEGATE, properties.get(AIAPIConstants.FAULT_FAILOVER_ACTION));
        PowerMockito.verifyPrivate(mediator, Mockito.never()).invoke("handleFailover", Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.anyBoolean());
    }

    @Test
    public void testHandleFailoverDoesNotSuspendWhenSynapseRefusedTheCallAsSuspended() throws Exception {

        properties.put(SynapseConstants.ERROR_CODE, SynapseConstants.ENDPOINT_ADDRESS_NONE_READY); // 303001
        AIAPIMediator mediator = exhaustedLastFallbackMediator();
        Whitebox.invokeMethod(mediator, "handleFailover", messageContext, new LLMProviderConfiguration(),
                properties.get(AIAPIConstants.FAILOVER_CONFIGS), true, true);

        PowerMockito.verifyPrivate(mediator, Mockito.never()).invoke("suspendTargetEndpoint", Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void testHandleFailoverStillFailsOverWithoutSuspendingWhenTheErrorCameBeforeTheCall() throws Exception {

        // An auth mediator failed before the <call>: the fallback is still tried, but nothing is suspended.
        properties.put(AIAPIConstants.LLM_CALL_SENT, AIAPIConstants.LLM_CALL_NOT_SENT);
        ModelEndpointDTO target = new ModelEndpointDTO();
        target.setEndpointId("primary");
        target.setModel("gpt-4o");
        ModelEndpointDTO fallback = new ModelEndpointDTO();
        fallback.setEndpointId("fallback");
        fallback.setModel("gpt-4o-mini");
        Map<String, Object> failoverConfigs = new HashMap<>();
        failoverConfigs.put(AIAPIConstants.FAILOVER_TARGET_MODEL_ENDPOINT, target);
        failoverConfigs.put(AIAPIConstants.SUSPEND_DURATION, 30000L);
        failoverConfigs.put(AIAPIConstants.FAILOVER_ENDPOINTS, new ArrayList<>(Arrays.asList(fallback)));
        failoverConfigs.put(AIAPIConstants.REQUEST_PAYLOAD, "{}");

        AIAPIMediator mediator = PowerMockito.spy(new AIAPIMediator());
        PowerMockito.doNothing().when(mediator, "updateJsonPayloadWithRequestPayload", Mockito.any(), Mockito.any());
        PowerMockito.doNothing().when(mediator, "updateRequestMetadata", Mockito.any(), Mockito.any());
        PowerMockito.doNothing().when(mediator, "updateTargetEndpoint", Mockito.any(), Mockito.anyInt(),
                Mockito.any());
        PowerMockito.doNothing().when(mediator, "applyFailoverRequestTimeout", Mockito.any(), Mockito.any());
        LLMProviderConfiguration providerConfiguration = new LLMProviderConfiguration();
        // An input source handleFailover doesn't act on, so only the suspension and the target switch are tested.
        PowerMockito.doReturn(new LLMProviderMetadata("model", "unsupported", "$.model", false,
                AIAPIConstants.ANALYTICS_CATEGORY_TOKEN_USAGE)).when(mediator, "getTargetModelMetadata", Mockito.any());

        Whitebox.invokeMethod(mediator, "handleFailover", messageContext, providerConfiguration, failoverConfigs,
                false, true);

        PowerMockito.verifyPrivate(mediator, Mockito.never()).invoke("suspendTargetEndpoint", Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
        PowerMockito.verifyPrivate(mediator).invoke("updateTargetEndpoint", Mockito.any(), Mockito.eq(1),
                Mockito.eq(fallback));
    }

    @Test
    public void testMediateDelegatesWhenProcessFaultFlowThrowsOnTheFaultPath() {

        // No policy lookup data: the provider is missing, which exits through the same earlyExit path as any
        // exception caught by the outer catch.
        AIAPIMediator mediator = new AIAPIMediator();
        mediator.setLlmProviderId("no-such-provider");
        mediator.setDirection(AIAPIConstants.TRAFFIC_FLOW_DIRECTION_FAULT);

        Assert.assertTrue(mediator.mediate(messageContext));
        Assert.assertEquals(AIAPIConstants.FAULT_FAILOVER_ACTION_DELEGATE,
                properties.get(AIAPIConstants.FAULT_FAILOVER_ACTION));
    }

    @Test
    public void testHandleFailoverIgnoresTheSuspendedCodeOnTheErrorReplyPath() throws Exception {

        // A stale 303001 must not stop the suspension of a real failure on the error-reply path.
        properties.put(SynapseConstants.ERROR_CODE, SynapseConstants.ENDPOINT_ADDRESS_NONE_READY);
        ModelEndpointDTO target = new ModelEndpointDTO();
        target.setEndpointId("primary");
        target.setModel("gpt-4o");
        Map<String, Object> failoverConfigs = new HashMap<>();
        failoverConfigs.put(AIAPIConstants.FAILOVER_TARGET_MODEL_ENDPOINT, target);
        failoverConfigs.put(AIAPIConstants.SUSPEND_DURATION, 30000L);
        failoverConfigs.put(AIAPIConstants.FAILOVER_ENDPOINTS, new ArrayList<ModelEndpointDTO>());

        AIAPIMediator mediator = PowerMockito.spy(new AIAPIMediator());
        PowerMockito.doNothing().when(mediator, "suspendTargetEndpoint", Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any());
        Whitebox.invokeMethod(mediator, "handleFailover", messageContext, new LLMProviderConfiguration(),
                failoverConfigs, true, false);

        PowerMockito.verifyPrivate(mediator).invoke("suspendTargetEndpoint", Mockito.any(), Mockito.eq("primary"),
                Mockito.any(), Mockito.any());
    }

}
