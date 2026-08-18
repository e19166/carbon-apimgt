/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.governance.rest.api.util;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;
import org.wso2.carbon.apimgt.governance.api.model.ArtifactType;
import org.wso2.carbon.apimgt.governance.api.model.RuleSeverity;
import org.wso2.carbon.apimgt.governance.api.model.RuleType;
import org.wso2.carbon.apimgt.governance.api.model.RuleViolation;
import org.wso2.carbon.apimgt.governance.api.model.RulesetInfo;
import org.wso2.carbon.apimgt.governance.impl.ComplianceManager;
import org.wso2.carbon.apimgt.governance.impl.RulesetManager;
import org.wso2.carbon.apimgt.governance.rest.api.dto.RulesetValidationResultWithoutRulesDTO;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tests that the ruleset status reported to the portals is decided only by the violations whose severity affects
 * compliance for that ruleset, while every violation stays visible to the user.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({ComplianceAPIUtil.class})
public class ComplianceAPIUtilSeverityTest {

    private static final String ARTIFACT_REF_ID = "d090cf7c-d1ab-491c-9357-b55a47e49ef2";
    private static final String RULESET_ID = "7ca1bcae-6feb-4c4b-9252-78e034b8b89e";
    private static final String ORGANIZATION = "carbon.super";

    private ComplianceManager complianceManager;
    private RulesetManager rulesetManager;

    @Before
    public void setUp() throws Exception {

        complianceManager = Mockito.mock(ComplianceManager.class);
        rulesetManager = Mockito.mock(RulesetManager.class);
        PowerMockito.whenNew(ComplianceManager.class).withNoArguments().thenReturn(complianceManager);
        PowerMockito.whenNew(RulesetManager.class).withNoArguments().thenReturn(rulesetManager);
    }

    /**
     * Build a rule violation carrying the given severity
     *
     * @param ruleName Name of the violated rule
     * @param severity Severity of the violated rule, may be null
     * @return RuleViolation
     */
    private RuleViolation violation(String ruleName, RuleSeverity severity) {

        RuleViolation ruleViolation = new RuleViolation();
        ruleViolation.setRuleName(ruleName);
        ruleViolation.setRulesetId(RULESET_ID);
        ruleViolation.setSeverity(severity);
        return ruleViolation;
    }

    /**
     * Invoke the ruleset validation mapping for a ruleset configured with the given severities
     *
     * @param configuredSeverities Comma separated severities stored against the ruleset, may be null
     * @param ruleViolations       Violations the compliance manager should report
     * @return RulesetValidationResultWithoutRulesDTO as returned to the portals
     * @throws Exception If the mapping fails
     */
    private RulesetValidationResultWithoutRulesDTO evaluate(String configuredSeverities,
                                                            List<RuleViolation> ruleViolations) throws Exception {

        Mockito.when(complianceManager.getRuleViolations(ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(ArtifactType.class), ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString()))
                .thenReturn(ruleViolations);
        Mockito.when(rulesetManager.getComplianceAffectingSeverities(ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString()))
                .thenReturn(configuredSeverities);

        RulesetInfo rulesetInfo = new RulesetInfo();
        rulesetInfo.setId(RULESET_ID);
        rulesetInfo.setName("Severity_Test_Ruleset");
        rulesetInfo.setRuleType(RuleType.API_DEFINITION);

        return Whitebox.invokeMethod(ComplianceAPIUtil.class, "getRulesetValidationResultsDTO",
                rulesetInfo, ARTIFACT_REF_ID, ArtifactType.API, ORGANIZATION, true);
    }

    @Test
    public void testRulesetPassesWhenOnlyInfoRulesAreViolated() throws Exception {

        RulesetValidationResultWithoutRulesDTO result = evaluate("ERROR,WARN", Arrays.asList(
                violation("api-description-check", RuleSeverity.INFO),
                violation("api-contact-check", RuleSeverity.INFO)));

        Assert.assertEquals(RulesetValidationResultWithoutRulesDTO.StatusEnum.PASSED, result.getStatus());
    }

    @Test
    public void testRulesetFailsWhenAnErrorRuleIsViolatedAlongsideInfoRules() throws Exception {

        RulesetValidationResultWithoutRulesDTO result = evaluate("ERROR,WARN", Arrays.asList(
                violation("api-version-prefix", RuleSeverity.ERROR),
                violation("api-description-check", RuleSeverity.INFO)));

        Assert.assertEquals(RulesetValidationResultWithoutRulesDTO.StatusEnum.FAILED, result.getStatus());
    }

    @Test
    public void testRulesetFailsWhenAWarnRuleIsViolated() throws Exception {

        RulesetValidationResultWithoutRulesDTO result = evaluate("ERROR,WARN",
                Collections.singletonList(violation("api-description-length", RuleSeverity.WARN)));

        Assert.assertEquals(RulesetValidationResultWithoutRulesDTO.StatusEnum.FAILED, result.getStatus());
    }

    @Test
    public void testRulesetFailsOnInfoViolationWhenNothingIsConfigured() throws Exception {

        RulesetValidationResultWithoutRulesDTO result = evaluate(null,
                Collections.singletonList(violation("api-description-check", RuleSeverity.INFO)));

        Assert.assertEquals("A ruleset with nothing configured must keep failing on info violations",
                RulesetValidationResultWithoutRulesDTO.StatusEnum.FAILED, result.getStatus());
    }

    @Test
    public void testRulesetPassesWhenNothingIsViolated() throws Exception {

        RulesetValidationResultWithoutRulesDTO result = evaluate("ERROR,WARN", Collections.emptyList());

        Assert.assertEquals(RulesetValidationResultWithoutRulesDTO.StatusEnum.PASSED, result.getStatus());
    }

    @Test
    public void testRulesetFailsWhenAViolationSeverityCannotBeResolved() throws Exception {

        RulesetValidationResultWithoutRulesDTO result = evaluate("ERROR",
                Collections.singletonList(violation("malformed-severity-rule", null)));

        Assert.assertEquals("A violation of an unresolved severity must still fail the ruleset",
                RulesetValidationResultWithoutRulesDTO.StatusEnum.FAILED, result.getStatus());
    }

    @Test
    public void testRulesetIsUnappliedWhenItWasNotEvaluated() throws Exception {

        RulesetInfo rulesetInfo = new RulesetInfo();
        rulesetInfo.setId(RULESET_ID);
        rulesetInfo.setName("Severity_Test_Ruleset");
        rulesetInfo.setRuleType(RuleType.API_DEFINITION);

        RulesetValidationResultWithoutRulesDTO result = Whitebox.invokeMethod(ComplianceAPIUtil.class,
                "getRulesetValidationResultsDTO", rulesetInfo, ARTIFACT_REF_ID, ArtifactType.API, ORGANIZATION,
                false);

        Assert.assertEquals("Severity filtering must not disturb the unapplied state",
                RulesetValidationResultWithoutRulesDTO.StatusEnum.UNAPPLIED, result.getStatus());
    }
}
