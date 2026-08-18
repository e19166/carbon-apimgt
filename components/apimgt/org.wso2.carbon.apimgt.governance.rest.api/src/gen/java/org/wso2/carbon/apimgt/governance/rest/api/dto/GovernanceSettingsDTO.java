package org.wso2.carbon.apimgt.governance.rest.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import javax.validation.constraints.*;

/**
 * Governance settings and available capabilities.
 **/

import io.swagger.annotations.*;
import java.util.Objects;

import javax.xml.bind.annotation.*;
import org.wso2.carbon.apimgt.rest.api.common.annotations.Scope;
import com.fasterxml.jackson.annotation.JsonCreator;

import javax.validation.Valid;

@ApiModel(description = "Governance settings and available capabilities.")

public class GovernanceSettingsDTO   {
  
    private Boolean complianceAffectingSeveritiesEnabled = null;

  /**
   * Whether per ruleset compliance affecting severity filtering is available on this deployment. 
   **/
  public GovernanceSettingsDTO complianceAffectingSeveritiesEnabled(Boolean complianceAffectingSeveritiesEnabled) {
    this.complianceAffectingSeveritiesEnabled = complianceAffectingSeveritiesEnabled;
    return this;
  }

  
  @ApiModelProperty(example = "true", value = "Whether per ruleset compliance affecting severity filtering is available on this deployment. ")
  @JsonProperty("complianceAffectingSeveritiesEnabled")
  public Boolean isComplianceAffectingSeveritiesEnabled() {
    return complianceAffectingSeveritiesEnabled;
  }
  public void setComplianceAffectingSeveritiesEnabled(Boolean complianceAffectingSeveritiesEnabled) {
    this.complianceAffectingSeveritiesEnabled = complianceAffectingSeveritiesEnabled;
  }


  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    GovernanceSettingsDTO governanceSettings = (GovernanceSettingsDTO) o;
    return Objects.equals(complianceAffectingSeveritiesEnabled, governanceSettings.complianceAffectingSeveritiesEnabled);
  }

  @Override
  public int hashCode() {
    return Objects.hash(complianceAffectingSeveritiesEnabled);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class GovernanceSettingsDTO {\n");
    
    sb.append("    complianceAffectingSeveritiesEnabled: ").append(toIndentedString(complianceAffectingSeveritiesEnabled)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(java.lang.Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
}

