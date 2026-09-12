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

package org.wso2.carbon.apimgt.governance.impl.dao.impl;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.wso2.carbon.apimgt.governance.api.error.APIMGovExceptionCodes;
import org.wso2.carbon.apimgt.governance.api.error.APIMGovernanceException;
import org.wso2.carbon.apimgt.governance.api.model.APIMGovernancePolicy;
import org.wso2.carbon.apimgt.governance.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.governance.impl.util.APIMGovernanceDBUtil;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.APIManagerConfigurationService;
import org.wso2.carbon.apimgt.impl.dto.APIMGovernanceConfigDTO;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;

import javax.sql.DataSource;

/**
 * Tests how a failed severity aware write is reported, one vendor at a time.
 * <p>
 * Nothing probes the schema, so "the operator turned the configuration on but has not run the ALTER TABLE" reaches
 * the code as an ordinary SQLException. It has to be recognised, because left alone it becomes a 500 for what is a
 * deployment step the caller can act on. Every vendor spells that failure differently, and the one thing that must
 * never happen is a genuine fault being reported as a missing column, which would send an operator to alter a
 * schema that is already correct.
 * <p>
 * The failure is injected at the data source rather than produced by a database, so this runs in an ordinary build
 * on every platform. That the real vendors actually raise these values is what
 * {@link SeverityColumnLiveDatabaseTest} confirms.
 */
public class SeverityColumnMissingClassificationTest {

    private static final String ORGANIZATION = "carbon.super";
    private static final String POLICY_ID = "e5c3d190-413a-4e58-9b44-0a3b1bb741d5";

    @Before
    public void enableTheFeature() {

        APIMGovernanceConfigDTO governanceConfig = new APIMGovernanceConfigDTO();
        governanceConfig.setPerPolicySeverityFilteringEnabled(true);

        APIManagerConfiguration configuration = Mockito.mock(APIManagerConfiguration.class);
        Mockito.when(configuration.getAPIMGovernanceConfigurationDto()).thenReturn(governanceConfig);

        APIManagerConfigurationService configurationService = Mockito.mock(APIManagerConfigurationService.class);
        Mockito.when(configurationService.getAPIManagerConfiguration()).thenReturn(configuration);

        ServiceReferenceHolder.getInstance().setAPIMConfigurationService(configurationService);
    }

    /**
     * Hand the DAO a connection which refuses the statement with the given failure
     *
     * @param failure Failure the driver would raise
     * @throws Exception If the data source cannot be injected
     */
    private void failEveryStatementWith(SQLException failure) throws Exception {

        Connection connection = Mockito.mock(Connection.class);
        Mockito.when(connection.prepareStatement(ArgumentMatchers.anyString())).thenThrow(failure);

        DataSource dataSource = Mockito.mock(DataSource.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);

        setDataSource(dataSource);
    }

    /**
     * Replace the data source the governance component reads through
     *
     * @param dataSource Data source to install, null to leave the component without one
     * @throws Exception If the field cannot be written
     */
    private void setDataSource(DataSource dataSource) throws Exception {

        Field field = APIMGovernanceDBUtil.class.getDeclaredField("dataSource");
        field.setAccessible(true);
        field.set(null, dataSource);
    }

    /**
     * Build a policy carrying a severity selection, so the write names the optional column
     *
     * @return Policy to write
     */
    private APIMGovernancePolicy policyWithSeverities() {

        APIMGovernancePolicy governancePolicy = new APIMGovernancePolicy();
        governancePolicy.setId(POLICY_ID);
        governancePolicy.setName("Severity_Test_Policy");
        governancePolicy.setCreatedBy("admin");
        governancePolicy.setUpdatedBy("admin");
        governancePolicy.setRulesetIds(Collections.emptyList());
        governancePolicy.setLabels(Collections.emptyList());
        governancePolicy.setActions(Collections.emptyList());
        governancePolicy.setGovernableStates(Collections.emptyList());
        governancePolicy.setComplianceAffectingSeverities("ERROR,WARN");
        return governancePolicy;
    }

    /**
     * Attempt a create which names the optional column and return how it was reported
     *
     * @param failure Failure the driver raises
     * @return Exception the DAO reported
     * @throws Exception If the data source cannot be injected
     */
    private APIMGovernanceException createFailingWith(SQLException failure) throws Exception {

        failEveryStatementWith(failure);
        try {
            GovernancePolicyMgtDAOImpl.getInstance().createGovernancePolicy(policyWithSeverities(), ORGANIZATION);
            Assert.fail("A refused statement must be reported rather than swallowed");
            return null;
        } catch (APIMGovernanceException reported) {
            return reported;
        }
    }

    /**
     * Assert that the given driver failure is reported as a missing column, with the remedy in the message
     *
     * @param vendor  Vendor the values come from, named so a failure says which one regressed
     * @param failure Failure the driver raises
     * @throws Exception If the attempt cannot be made
     */
    private void assertReportedAsMissingColumn(String vendor, SQLException failure) throws Exception {

        APIMGovernanceException reported = createFailingWith(failure);

        Assert.assertEquals(vendor + " reports a missing column this way, so it must be recognised as one",
                APIMGovExceptionCodes.PER_POLICY_SEVERITY_FILTERING_UNAVAILABLE.getErrorCode(),
                reported.getErrorHandler().getErrorCode());
        Assert.assertEquals("A deployment step the caller can act on is a request error, not a server fault",
                400, reported.getErrorHandler().getHttpStatusCode());
        Assert.assertTrue("The message must name the statement to run, since that is the whole remedy",
                String.valueOf(reported.getMessage()).contains("ALTER TABLE"));
    }

    @Test
    public void testPostgreSqlMissingColumnIsRecognised() throws Exception {

        // PostgreSQL is precise enough about the SQLState that the vendor code is not needed
        assertReportedAsMissingColumn("PostgreSQL",
                new SQLException("ERROR: column \"compliance_affecting_severities\" of relation \"gov_policy\" does "
                        + "not exist", "42703", 0));
    }

    @Test
    public void testMySqlMissingColumnIsRecognised() throws Exception {

        assertReportedAsMissingColumn("MySQL",
                new SQLException("Unknown column 'COMPLIANCE_AFFECTING_SEVERITIES' in 'field list'", "42S22", 1054));
    }

    @Test
    public void testSqlServerMissingColumnIsRecognised() throws Exception {

        // The SQL Server driver's SQLState for this varies by version, so the vendor code is what carries it
        assertReportedAsMissingColumn("SQL Server",
                new SQLException("Invalid column name 'COMPLIANCE_AFFECTING_SEVERITIES'.", "S0001", 207));
    }

    @Test
    public void testOracleMissingColumnIsRecognised() throws Exception {

        // Oracle buckets this under the generic 42000 syntax state, which is far too broad to match on, so only
        // the code can be trusted here.
        assertReportedAsMissingColumn("Oracle",
                new SQLException("ORA-00904: \"COMPLIANCE_AFFECTING_SEVERITIES\": invalid identifier", "42000", 904));
    }

    @Test
    public void testDb2MissingColumnIsRecognised() throws Exception {

        assertReportedAsMissingColumn("DB2",
                new SQLException("COMPLIANCE_AFFECTING_SEVERITIES IS NOT VALID IN THE CONTEXT WHERE IT IS USED",
                        "42703", -206));
    }

    @Test
    public void testH2MissingColumnIsRecognised() throws Exception {

        assertReportedAsMissingColumn("H2",
                new SQLException("Column \"COMPLIANCE_AFFECTING_SEVERITIES\" not found", "42122", 42122));
    }

    @Test
    public void testAMissingColumnReportedOnlyByAChainedExceptionIsRecognised() throws Exception {

        // Drivers and pools do not always put the informative exception outermost, so both the chain a driver
        // attaches and the cause a pool wraps it in have to be walked.
        SQLException informative = new SQLException("Unknown column", "42S22", 1054);
        SQLException outer = new SQLException("Statement failed", "HY000", 0);
        outer.setNextException(informative);

        assertReportedAsMissingColumn("a driver chaining the detail", outer);
    }

    // A read cannot substitute anything for a column it could not select, so it fails with the code its own path
    // uses. What it must never do is fail while building the message: every one of these codes carries a %s, and
    // an argument that was not passed turns the real error into MissingFormatArgumentException, which the global
    // mapper then reports as "an unknown exception". The cause is then invisible in the log.

    @Test
    public void testAnOrganizationReadReportsTheFailureRatherThanCrashingOnTheMessage() throws Exception {

        failEveryStatementWith(new SQLException("Column \"COMPLIANCE_AFFECTING_SEVERITIES\" not found",
                "42122", 42122));

        try {
            GovernancePolicyMgtDAOImpl.getInstance().getComplianceAffectingSeverities(ORGANIZATION);
            Assert.fail("A failed read must be reported");
        } catch (RuntimeException crash) {
            Assert.fail("The read must report the database failure, not throw while formatting its own message: "
                    + crash);
        } catch (APIMGovernanceException reported) {
            Assert.assertEquals(APIMGovExceptionCodes.ERROR_WHILE_GETTING_POLICIES.getErrorCode(),
                    reported.getErrorHandler().getErrorCode());
            Assert.assertTrue("The message must name the organization the read was for, which is the argument "
                            + "whose absence caused the crash",
                    reported.getErrorHandler().getErrorDescription().contains(ORGANIZATION));
            Assert.assertFalse("No format specifier may survive into the message",
                    reported.getErrorHandler().getErrorDescription().contains("%s"));
        }
    }

    @Test
    public void testASinglePolicyReadReportsTheFailureRatherThanCrashingOnTheMessage() throws Exception {

        failEveryStatementWith(new SQLException("Unknown column 'COMPLIANCE_AFFECTING_SEVERITIES' in 'field list'",
                "42S22", 1054));

        try {
            GovernancePolicyMgtDAOImpl.getInstance().getComplianceAffectingSeverities(POLICY_ID, ORGANIZATION);
            Assert.fail("A failed read must be reported");
        } catch (RuntimeException crash) {
            Assert.fail("The read must report the database failure, not throw while formatting its own message: "
                    + crash);
        } catch (APIMGovernanceException reported) {
            Assert.assertEquals(APIMGovExceptionCodes.ERROR_WHILE_GETTING_POLICY_BY_ID.getErrorCode(),
                    reported.getErrorHandler().getErrorCode());
            Assert.assertTrue("The message must name the policy the read was for",
                    reported.getErrorHandler().getErrorDescription().contains(POLICY_ID));
            Assert.assertFalse("No format specifier may survive into the message",
                    reported.getErrorHandler().getErrorDescription().contains("%s"));
        }
    }

    @Test
    public void testAGenuineFaultIsNotReportedAsAMissingColumn() throws Exception {

        // The failure this must never produce. Telling an operator to alter a schema that is already correct
        // sends them to change the one thing that is not wrong, while the deadlock goes unlooked at.
        APIMGovernanceException reported = createFailingWith(
                new SQLException("Deadlock found when trying to get lock", "40001", 1213));

        Assert.assertEquals("A deadlock is a server fault and must keep the code the create path already used",
                APIMGovExceptionCodes.ERROR_WHILE_CREATING_POLICY.getErrorCode(),
                reported.getErrorHandler().getErrorCode());
        Assert.assertEquals(500, reported.getErrorHandler().getHttpStatusCode());
    }

    @Test
    public void testADeniedPermissionIsNotReportedAsAMissingColumn() throws Exception {

        APIMGovernanceException reported = createFailingWith(
                new SQLException("permission denied for table gov_policy", "42501", 0));

        Assert.assertEquals("A denied permission is not a missing column, and altering the table would not fix it",
                APIMGovExceptionCodes.ERROR_WHILE_CREATING_POLICY.getErrorCode(),
                reported.getErrorHandler().getErrorCode());
    }

    @After
    public void forgetTheInjectedState() throws Exception {

        ServiceReferenceHolder.getInstance().setAPIMConfigurationService(null);
        // The injected data source outlives this test otherwise, and a later one in the same JVM would then reach
        // a database it was never given.
        setDataSource(null);
    }
}
