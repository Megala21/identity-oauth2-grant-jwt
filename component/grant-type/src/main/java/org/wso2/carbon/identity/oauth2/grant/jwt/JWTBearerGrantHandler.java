/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
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
 * under the License
 */

package org.wso2.carbon.identity.oauth2.grant.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.*;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationConstants;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationManagementUtil;
import org.wso2.carbon.identity.base.IdentityException;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCache;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCacheEntry;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCacheKey;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.dto.OAuth2AccessTokenRespDTO;
import org.wso2.carbon.identity.oauth2.grant.jwt.cache.JWTCache;
import org.wso2.carbon.identity.oauth2.grant.jwt.cache.JWTCacheEntry;
import org.wso2.carbon.identity.oauth2.model.RequestParameter;
import org.wso2.carbon.identity.oauth2.token.OAuthTokenReqMessageContext;
import org.wso2.carbon.identity.oauth2.token.handlers.grant.AbstractAuthorizationGrantHandler;
import org.wso2.carbon.identity.oauth2.util.ClaimsUtil;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.identity.oauth2.validators.jwt.JWKSBasedJWTValidator;
import org.wso2.carbon.idp.mgt.IdentityProviderManagementException;
import org.wso2.carbon.idp.mgt.IdentityProviderManager;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.io.IOException;
import java.io.InputStream;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Class to handle JSON Web Token(JWT) grant type
 */
public class JWTBearerGrantHandler extends AbstractAuthorizationGrantHandler {

    private static final String OAUTH_SPLIT_AUTHZ_USER_3_WAY = "OAuth.SplitAuthzUser3Way";
    private static final String DEFAULT_IDP_NAME = "default";
    private static Log log = LogFactory.getLog(JWTBearerGrantHandler.class);
    private static final String OIDC_IDP_ENTITY_ID = "IdPEntityId";
    private static final String ERROR_GET_RESIDENT_IDP =
            "Error while getting Resident Identity Provider of '%s' tenant.";

    private String tenantDomain;
    private int validityPeriod;
    private JWTCache jwtCache;
    private boolean cacheUsedJTI;

    /**
     * Initialize the JWT cache.
     *
     * @throws org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception
     */
    public void init() throws IdentityOAuth2Exception {
        super.init();
        String resourceName = JWTConstants.PROPERTIES_FILE;

        ClassLoader loader = JWTBearerGrantHandler.class.getClassLoader();
        Properties prop = new Properties();
        InputStream resourceStream = loader.getResourceAsStream(resourceName);
        try {
            prop.load(resourceStream);
            validityPeriod = Integer.parseInt(prop.getProperty(JWTConstants.VALIDITY_PERIOD));
            cacheUsedJTI = Boolean.parseBoolean(prop.getProperty(JWTConstants.CACHE_USED_JTI));
            if (cacheUsedJTI) {
                this.jwtCache = JWTCache.getInstance();
            }
        } catch (IOException e) {
            throw new IdentityOAuth2Exception("Can not find the file", e);
        } catch (NumberFormatException e) {
            throw new IdentityOAuth2Exception("Invalid Validity period", e);
        } finally {
            try {
                resourceStream.close();
            } catch (IOException e) {
                log.error("Error while closing the stream");
            }
        }
    }

    /**
     * Get resident Identity Provider.
     *
     * @param tenantDomain tenant Domain
     * @param jwtIssuer    issuer extracted from assertion
     * @return resident Identity Provider
     * @throws IdentityOAuth2Exception
     */
    private IdentityProvider getResidentityIDPForIssuer(String tenantDomain, String jwtIssuer) throws IdentityOAuth2Exception {
        String issuer = StringUtils.EMPTY;
        IdentityProvider residentIdentityProvider = null;
        try {
            residentIdentityProvider = IdentityProviderManager.getInstance().getResidentIdP(tenantDomain);
        } catch (IdentityProviderManagementException e) {
            String errorMsg = String.format(ERROR_GET_RESIDENT_IDP, tenantDomain);
            throw new IdentityOAuth2Exception(errorMsg, e);
        }
        FederatedAuthenticatorConfig[] fedAuthnConfigs = residentIdentityProvider.getFederatedAuthenticatorConfigs();
        FederatedAuthenticatorConfig oauthAuthenticatorConfig =
                IdentityApplicationManagementUtil.getFederatedAuthenticator(fedAuthnConfigs,
                        IdentityApplicationConstants.Authenticator.OIDC.NAME);
        if (oauthAuthenticatorConfig != null) {
            issuer = IdentityApplicationManagementUtil.getProperty(oauthAuthenticatorConfig.getProperties(),
                    OIDC_IDP_ENTITY_ID).getValue();
        }
        return jwtIssuer.equals(issuer) ? residentIdentityProvider : null;
    }


    /**
     * We're validating the JWT token that we receive from the request. Through the assertion parameter is the POST
     * request. A request format that we handle here looks like,
     * <p/>
     * POST /token.oauth2 HTTP/1.1
     * Host: as.example.com
     * Content-Type: application/x-www-form-urlencoded
     * <p/>
     * grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer
     * &assertion=eyJhbGciOiJFUzI1NiJ9.
     * eyJpc3Mi[...omitted for brevity...].
     *
     * @param tokReqMsgCtx Token message request context
     * @return true if validation is successful, false otherwise
     * @throws org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception
     */
    @Override
    public boolean validateGrant(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {
//        super.validateGrant(tokReqMsgCtx); //This line was commented to work with IS 5.2.0

        SignedJWT signedJWT;
        IdentityProvider identityProvider;
        String tokenEndPointAlias = null;
        JWTClaimsSet claimsSet;

        tenantDomain = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getTenantDomain();
        if (StringUtils.isEmpty(tenantDomain)) {
            tenantDomain = MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
        }
        signedJWT = getSignedJWT(tokReqMsgCtx);
        if (signedJWT == null) {
            handleException("No Valid Assertion was found for " + JWTConstants.OAUTH_JWT_BEARER_GRANT_TYPE);
        }
        claimsSet = getClaimSet(signedJWT);
        if (claimsSet == null) {
            handleException("Claim values are empty in the given JSON Web Token");
        }

        String jwtIssuer = claimsSet.getIssuer();
        String subject = resolveSubject(claimsSet);
        List<String> audience = claimsSet.getAudience();
        Date expirationTime = claimsSet.getExpirationTime();
        Date notBeforeTime = claimsSet.getNotBeforeTime();
        Date issuedAtTime = claimsSet.getIssueTime();
        String jti = claimsSet.getJWTID();
        Map<String, Object> customClaims = claimsSet.getClaims();
        boolean signatureValid;
        boolean audienceFound = false;
        long currentTimeInMillis = System.currentTimeMillis();
        long timeStampSkewMillis = OAuthServerConfiguration.getInstance().getTimeStampSkewInSeconds() * 1000;

        if (StringUtils.isEmpty(jwtIssuer) || StringUtils.isEmpty(subject) || expirationTime == null || audience == null) {
            handleException("Mandatory fields(Issuer, Subject, Expiration time or Audience) are empty in the given JSON Web Token.");
        }
        try {
            identityProvider = IdentityProviderManager.getInstance().getIdPByName(jwtIssuer, tenantDomain);
            if (identityProvider != null) {
                // if no IDPs were found for a given name, the IdentityProviderManager returns a dummy IDP with the
                // name "default". We need to handle this case.
                if (StringUtils.equalsIgnoreCase(identityProvider.getIdentityProviderName(), DEFAULT_IDP_NAME)) {
                    //check whether this jwt was issued by the resident identity provider
                    identityProvider = getResidentityIDPForIssuer(tenantDomain, jwtIssuer);
                    if (identityProvider == null) {
                        handleException("No Registered IDP found for the JWT with issuer name : " + jwtIssuer);
                    }
                }

                tokenEndPointAlias = getTokenEndpointAlias(identityProvider);
            } else {
                handleException("No Registered IDP found for the JWT with issuer name : " + jwtIssuer);
            }

            signatureValid = validateSignature(signedJWT, identityProvider);
            if (signatureValid) {
                if (log.isDebugEnabled()) {
                    log.debug("Signature/MAC validated successfully.");
                }
            } else {
                handleException("Signature or Message Authentication invalid.");
            }

            if (Boolean.parseBoolean(IdentityUtil.getProperty(OAUTH_SPLIT_AUTHZ_USER_3_WAY))) {
                tokReqMsgCtx.setAuthorizedUser(OAuth2Util.getUserFromUserName(subject));
            } else {
                tokReqMsgCtx.setAuthorizedUser(AuthenticatedUser
                        .createLocalAuthenticatedUserFromSubjectIdentifier(subject));
            }
            if (log.isDebugEnabled()) {
                log.debug("Subject(sub) found in JWT: " + subject);
                log.debug(subject + " set as the Authorized User.");
            }

            tokReqMsgCtx.setScope(tokReqMsgCtx.getOauth2AccessTokenReqDTO().getScope());

            if (StringUtils.isEmpty(tokenEndPointAlias)) {
                handleException("Token Endpoint alias of the local Identity Provider has not been " +
                        "configured for " + identityProvider.getIdentityProviderName());
            }
            for (String aud : audience) {
                if (StringUtils.equals(tokenEndPointAlias, aud)) {
                    if (log.isDebugEnabled()) {
                        log.debug(tokenEndPointAlias + " of IDP was found in the list of audiences.");
                    }
                    audienceFound = true;
                    break;
                }
            }
            if (!audienceFound) {
                handleException("None of the audience values matched the tokenEndpoint Alias " + tokenEndPointAlias);
            }
            boolean checkedExpirationTime = checkExpirationTime(expirationTime, currentTimeInMillis,
                    timeStampSkewMillis);
            if (checkedExpirationTime) {
                if (log.isDebugEnabled()) {
                    log.debug("Expiration Time(exp) of JWT was validated successfully.");
                }
            }
            if (notBeforeTime == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Not Before Time(nbf) not found in JWT. Continuing Validation");
                }
            } else {
                boolean checkedNotBeforeTime = checkNotBeforeTime(notBeforeTime, currentTimeInMillis,
                        timeStampSkewMillis);
                if (checkedNotBeforeTime) {
                    if (log.isDebugEnabled()) {
                        log.debug("Not Before Time(nbf) of JWT was validated successfully.");
                    }
                }
            }
            if (issuedAtTime == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Issued At Time(iat) not found in JWT. Continuing Validation");
                }
            } else {
                boolean checkedValidityToken = checkValidityOfTheToken(issuedAtTime, currentTimeInMillis,
                        timeStampSkewMillis);
                if (checkedValidityToken) {
                    if (log.isDebugEnabled()) {
                        log.debug("Issued At Time(iat) of JWT was validated successfully.");
                    }
                }
            }
            if (cacheUsedJTI && (jti != null)) {
                JWTCacheEntry entry = (JWTCacheEntry) jwtCache.getValueFromCache(jti);
                if (entry != null) {
                    if (checkCachedJTI(jti, signedJWT, entry, currentTimeInMillis, timeStampSkewMillis)) {
                        if (log.isDebugEnabled()) {
                            log.debug("JWT id: " + jti + " not found in the cache.");
                            log.debug("jti of the JWT has been validated successfully.");
                        }
                    }
                }
            } else {
                if (log.isDebugEnabled()) {
                    if (!cacheUsedJTI) {
                        log.debug("List of used JSON Web Token IDs are not maintained. Continue Validation");
                    }
                    if (jti == null) {
                        log.debug("JSON Web Token ID(jti) not found in JWT. Continuing Validation");
                    }
                }
            }
            if (customClaims == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No custom claims found. Continue validating other claims.");
                }
            } else {
                boolean customClaimsValidated = validateCustomClaims(claimsSet.getClaims());
                if (!customClaimsValidated) {
                    handleException("Custom Claims in the JWT were invalid");
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("JWT Token was validated successfully");
            }
            if (cacheUsedJTI) {
                jwtCache.addToCache(jti, new JWTCacheEntry(signedJWT));
            }
            if (log.isDebugEnabled()) {
                log.debug("JWT Token was added to the cache successfully");
            }
        } catch (IdentityProviderManagementException e) {
            handleException("Error while getting the Federated Identity Provider ");
        } catch (JOSEException e) {
            handleException("Error when verifying signature");
        }
        if (log.isDebugEnabled()) {
            log.debug("Issuer(iss) of the JWT validated successfully");
        }
        return true;
    }

    @Override
    public OAuth2AccessTokenRespDTO issue(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {

        OAuth2AccessTokenRespDTO responseDTO = super.issue(tokReqMsgCtx);
        String[] scope = tokReqMsgCtx.getScope();
        if (OAuth2Util.isOIDCAuthzRequest(scope)) {
            handleCustomClaims(tokReqMsgCtx, responseDTO);
        }
        return responseDTO;
    }

    private void handleCustomClaims(OAuthTokenReqMessageContext tokReqMsgCtx, OAuth2AccessTokenRespDTO responseDTO) throws
            IdentityOAuth2Exception {
        SignedJWT signedJWT = getSignedJWT(tokReqMsgCtx);

        // Ignore null checks since the execution comes to this phase only if validate grant phase is passed.
        // Hence, continuing without null check.
        JWTClaimsSet claimsSet = getClaimSet(signedJWT);

        Map<String, String> customClaimMap = getClaims(claimsSet);
        String jwtIssuer = claimsSet.getIssuer();

        String tenantDomain = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getTenantDomain();
        if (StringUtils.isBlank(tenantDomain)) {
            tenantDomain = org.wso2.carbon.base.MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
        }

        IdentityProvider identityProvider = null;
        try {
            identityProvider = IdentityProviderManager.getInstance().getIdPByName(jwtIssuer, tenantDomain);
        } catch (IdentityProviderManagementException e) {
            handleException(
                    "Error while getting IDP based on the jwt issuer " + jwtIssuer + "  for the tenant " + "domain "
                            + tenantDomain, e);
        }

        boolean localClaimDialect = identityProvider.getClaimConfig().isLocalClaimDialect();
        ClaimMapping[] idPClaimMappings = identityProvider.getClaimConfig().getClaimMappings();
        Map<String, String> localClaims;

        if (IdentityApplicationConstants.RESIDENT_IDP_RESERVED_NAME
                .equals(identityProvider.getIdentityProviderName())) {
            localClaims = handleClaimsForResidentIDP(customClaimMap, identityProvider);
        } else {
            localClaims = handleClaimsForIDP(customClaimMap, tenantDomain, identityProvider, localClaimDialect,
                    idPClaimMappings);
        }

        // ########################### all claims are in local dialect ############################

        if (localClaims != null && localClaims.size() > 0) {
            Map<String, String> oidcClaims;
            try {
                oidcClaims = ClaimsUtil.convertClaimsToOIDCDialect(tokReqMsgCtx, localClaims);
            } catch (IdentityApplicationManagementException | IdentityException e) {
                throw new IdentityOAuth2Exception("Error while converting user claims to OIDC dialect" + ".");
            }
            Map<ClaimMapping, String> claimMappings = FrameworkUtils.buildClaimMappings(oidcClaims);
            addUserAttributesToCache(responseDTO, tokReqMsgCtx, claimMappings);
        }

    }
    private Map<String, String> getClaims(JWTClaimsSet claimsSet) {

        Map<String, Object> customClaims = claimsSet.getClaims();
        Map<String, String> customClaimMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : customClaims.entrySet()) {
            Object value = entry.getValue();
            customClaimMap.put(entry.getKey(), value.toString());
        }
        return customClaimMap;
    }

    protected Map<String, String> handleClaimsForResidentIDP(Map<String, String> attributes, IdentityProvider
            identityProvider) {

        boolean localClaimDialect;
        Map<String, String> localClaims = new HashMap<>();
        localClaimDialect = identityProvider.getClaimConfig().isLocalClaimDialect();
        if (localClaimDialect) {
            localClaims = handleLocalClaims(attributes, identityProvider);
        } else {
            if (ClaimsUtil.isInLocalDialect(attributes)) {
                localClaims = attributes;
                if (log.isDebugEnabled()) {
                    log.debug("IDP claims dialect is not local. But claims are in local dialect " +
                            "for identity provider: " + identityProvider.getIdentityProviderName() +
                            ". Using attributes in assertion as the IDP claims.");
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("IDP claims dialect is not local. These claims are not handled for " +
                            "identity provider: " + identityProvider.getIdentityProviderName());
                }
            }

        }
        return localClaims;
    }

    protected Map<String, String> handleClaimsForIDP(Map<String, String> attributes, String tenantDomain,
            IdentityProvider identityProvider, boolean localClaimDialect,
            ClaimMapping[] idPClaimMappings) {

        Map<String, String> localClaims;
        if (localClaimDialect) {
            localClaims = handleLocalClaims(attributes, identityProvider);
        } else {
            if (idPClaimMappings.length > 0) {
                localClaims = ClaimsUtil.convertFederatedClaimsToLocalDialect(attributes, idPClaimMappings,
                        tenantDomain);
                if (log.isDebugEnabled()) {
                    log.debug("IDP claims dialect is not local. Converted claims for " +
                            "identity provider: " + identityProvider.getIdentityProviderName());
                }
            } else {
                localClaims = handleLocalClaims(attributes, identityProvider);
            }
        }
        return localClaims;
    }

    private Map<String, String> handleLocalClaims(Map<String, String> attributes, IdentityProvider identityProvider) {

        Map<String, String> localClaims = new HashMap<>();
        if (ClaimsUtil.isInLocalDialect(attributes)) {
            localClaims = attributes;
            if (log.isDebugEnabled()) {
                log.debug("Claims are in local dialect for " +
                        "identity provider: " + identityProvider.getIdentityProviderName() +
                        ". Using attributes in assertion as the IDP claims.");
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Claims are not in local dialect " +
                        "for identity provider: " + identityProvider.getIdentityProviderName() +
                        ". Not considering attributes in assertion.");
            }
        }
        return localClaims;
    }


    /**
     * the default implementation creates the subject from the Sub attribute.
     * To translate between the federated and local user store, this may need some mapping.
     * Override if needed
     *
     * @param claimsSet all the JWT claims
     * @return The subject, to be used
     */
    protected String resolveSubject(JWTClaimsSet claimsSet) {
        return claimsSet.getSubject();
    }

    /**
     * @param tokReqMsgCtx Token message request context
     * @return signedJWT
     */
    private SignedJWT getSignedJWT(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {
        RequestParameter[] params = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getRequestParameters();
        String assertion = null;
        SignedJWT signedJWT = null;
        for (RequestParameter param : params) {
            if (param.getKey().equals(JWTConstants.OAUTH_JWT_ASSERTION)) {
                assertion = param.getValue()[0];
                break;
            }
        }
        if (StringUtils.isEmpty(assertion)) {
            return null;
        }

        try {
            signedJWT = SignedJWT.parse(assertion);
            if (log.isDebugEnabled()) {
                logJWT(signedJWT);
            }
        } catch (ParseException e) {
            handleException("Error while parsing the JWT" + e.getMessage());
        }
        return signedJWT;
    }

    /**
     * @param signedJWT Signed JWT
     * @return Claim set
     */
    private JWTClaimsSet getClaimSet(SignedJWT signedJWT) throws IdentityOAuth2Exception {
        JWTClaimsSet claimsSet = null;
        try {
            claimsSet = signedJWT.getJWTClaimsSet();
        } catch (ParseException e) {
            handleException("Error when trying to retrieve claimsSet from the JWT");
        }
        return claimsSet;
    }

    /**
     * Get token endpoint alias
     *
     * @param identityProvider Identity provider
     * @return token endpoint alias
     */
    private String getTokenEndpointAlias(IdentityProvider identityProvider) {
        Property oauthTokenURL = null;
        String tokenEndPointAlias = null;
        if (IdentityApplicationConstants.RESIDENT_IDP_RESERVED_NAME.equals(
                identityProvider.getIdentityProviderName())) {
            try {
                identityProvider = IdentityProviderManager.getInstance().getResidentIdP(tenantDomain);
            } catch (IdentityProviderManagementException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Error while getting Resident IDP :" + e.getMessage());
                }
            }
            FederatedAuthenticatorConfig[] fedAuthnConfigs =
                    identityProvider.getFederatedAuthenticatorConfigs();
            FederatedAuthenticatorConfig oauthAuthenticatorConfig =
                    IdentityApplicationManagementUtil.getFederatedAuthenticator(fedAuthnConfigs,
                            IdentityApplicationConstants.Authenticator.OIDC.NAME);

            if (oauthAuthenticatorConfig != null) {
                oauthTokenURL = IdentityApplicationManagementUtil.getProperty(
                        oauthAuthenticatorConfig.getProperties(),
                        IdentityApplicationConstants.Authenticator.OIDC.OAUTH2_TOKEN_URL);
            }
            if (oauthTokenURL != null) {
                tokenEndPointAlias = oauthTokenURL.getValue();
                if (log.isDebugEnabled()) {
                    log.debug("Token End Point Alias of Resident IDP :" + tokenEndPointAlias);
                }
            }
        } else {
            tokenEndPointAlias = identityProvider.getAlias();
            if (log.isDebugEnabled()) {
                log.debug("Token End Point Alias of the Federated IDP: " + tokenEndPointAlias);
            }
        }
        return tokenEndPointAlias;
    }

    /**
     * The JWT MUST contain an exp (expiration) claim that limits the time window during which
     * the JWT can be used. The authorization server MUST reject any JWT with an expiration time
     * that has passed, subject to allowable clock skew between systems. Note that the
     * authorization server may reject JWTs with an exp claim value that is unreasonably far in the
     * future.
     *
     * @param expirationTime      Expiration time
     * @param currentTimeInMillis Current time
     * @param timeStampSkewMillis Time skew
     * @return true or false
     */
    private boolean checkExpirationTime(Date expirationTime, long currentTimeInMillis, long timeStampSkewMillis) throws IdentityOAuth2Exception {
        long expirationTimeInMillis = expirationTime.getTime();
        if ((currentTimeInMillis + timeStampSkewMillis) > expirationTimeInMillis) {
            handleException("JSON Web Token is expired." +
                    ", Expiration Time(ms) : " + expirationTimeInMillis +
                    ", TimeStamp Skew : " + timeStampSkewMillis +
                    ", Current Time : " + currentTimeInMillis + ". JWT Rejected and validation terminated");
        }
        return true;
    }

    /**
     * The JWT MAY contain an nbf (not before) claim that identifies the time before which the
     * token MUST NOT be accepted for processing.
     *
     * @param notBeforeTime       Not before time
     * @param currentTimeInMillis Current time
     * @param timeStampSkewMillis Time skew
     * @return true or false
     */
    private boolean checkNotBeforeTime(Date notBeforeTime, long currentTimeInMillis, long timeStampSkewMillis) throws IdentityOAuth2Exception {
        long notBeforeTimeMillis = notBeforeTime.getTime();
        if (currentTimeInMillis + timeStampSkewMillis < notBeforeTimeMillis) {
            handleException("JSON Web Token is used before Not_Before_Time." +
                    ", Not Before Time(ms) : " + notBeforeTimeMillis +
                    ", TimeStamp Skew : " + timeStampSkewMillis +
                    ", Current Time : " + currentTimeInMillis + ". JWT Rejected and validation terminated");
        }
        return true;
    }

    /**
     * The JWT MAY contain an iat (issued at) claim that identifies the time at which the JWT was
     * issued. Note that the authorization server may reject JWTs with an iat claim value that is
     * unreasonably far in the past
     *
     * @param issuedAtTime        Token issued time
     * @param currentTimeInMillis Current time
     * @param timeStampSkewMillis Time skew
     * @return true or false
     */
    private boolean checkValidityOfTheToken(Date issuedAtTime, long currentTimeInMillis, long timeStampSkewMillis) throws IdentityOAuth2Exception {
        long issuedAtTimeMillis = issuedAtTime.getTime();
        long rejectBeforeMillis = 1000L * 60 * validityPeriod;
        if (currentTimeInMillis + timeStampSkewMillis - issuedAtTimeMillis >
                rejectBeforeMillis) {
            handleException("JSON Web Token is issued before the allowed time." +
                    ", Issued At Time(ms) : " + issuedAtTimeMillis +
                    ", Reject before limit(ms) : " + rejectBeforeMillis +
                    ", TimeStamp Skew : " + timeStampSkewMillis +
                    ", Current Time : " + currentTimeInMillis + ". JWT Rejected and validation terminated");
        }
        return true;
    }

    /**
     * Method to check whether the JTI is already in the cache.
     *
     * @param jti                 JSON Token Id
     * @param signedJWT           Signed JWT
     * @param entry               Cache entry
     * @param currentTimeInMillis Current time
     * @param timeStampSkewMillis Skew time
     * @return true or false
     */
    private boolean checkCachedJTI(String jti, SignedJWT signedJWT, JWTCacheEntry entry, long currentTimeInMillis,
                                   long timeStampSkewMillis) throws IdentityOAuth2Exception {
        try {
            SignedJWT cachedJWT = entry.getJwt();
            long cachedJWTExpiryTimeMillis = cachedJWT.getJWTClaimsSet().getExpirationTime().getTime();
            if (currentTimeInMillis + timeStampSkewMillis > cachedJWTExpiryTimeMillis) {
                if (log.isDebugEnabled()) {
                    log.debug("JWT Token has been reused after the allowed expiry time : "
                            + cachedJWT.getJWTClaimsSet().getExpirationTime());
                }

                // Update the cache with the new JWT for the same JTI.
                this.jwtCache.addToCache(jti, new JWTCacheEntry(signedJWT));
                if (log.isDebugEnabled()) {
                    log.debug("jti of the JWT has been validated successfully and cache updated");
                }
            } else {
                    handleException("JWT Token \n" + signedJWT.getHeader().toJSONObject().toString() + "\n"
                            + signedJWT.getPayload().toJSONObject().toString() + "\n" +
                            "Has been replayed before the allowed expiry time : "
                            + cachedJWT.getJWTClaimsSet().getExpirationTime());
            }
        } catch (ParseException e) {
            handleException("Unable to parse the cached jwt assertion : " + entry.getEncodedJWt());
        }
        return true;
    }

    /**
     * @param signedJWT the signedJWT to be logged
     */
    private void logJWT(SignedJWT signedJWT) {
        log.debug("JWT Header: " + signedJWT.getHeader().toJSONObject().toString());
        log.debug("JWT Payload: " + signedJWT.getPayload().toJSONObject().toString());
        log.debug("Signature: " + signedJWT.getSignature().toString());
    }

    /**
     * Method to validate the signature of the JWT
     *
     * @param signedJWT signed JWT whose signature is to be verified
     * @param idp       Identity provider who issued the signed JWT
     * @return whether signature is valid, true if valid else false
     * @throws com.nimbusds.jose.JOSEException
     * @throws org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception
     */
    private boolean validateSignature(SignedJWT signedJWT, IdentityProvider idp)
            throws JOSEException, IdentityOAuth2Exception {

        boolean isJWKSEnabled = false;
        boolean hasJWKSUri = false;
        String jwksUri = null;

        String isJWKSEnalbedProperty = IdentityUtil.getProperty(JWTConstants.JWKS_VALIDATION_ENABLE_CONFIG);
        isJWKSEnabled = Boolean.parseBoolean(isJWKSEnalbedProperty);
        if (isJWKSEnabled) {
            if (log.isDebugEnabled()) {
                log.debug("JWKS based JWT validation enabled.");
            }
        }

        IdentityProviderProperty[] identityProviderProperties = idp.getIdpProperties();
        if (!ArrayUtils.isEmpty(identityProviderProperties)) {
            for (IdentityProviderProperty identityProviderProperty : identityProviderProperties) {
                if (StringUtils.equals(identityProviderProperty.getName(), JWTConstants.JWKS_URI)) {
                    hasJWKSUri = true;
                    jwksUri = identityProviderProperty.getValue();
                    if (log.isDebugEnabled()) {
                        log.debug("JWKS endpoint set for the identity provider : " + idp.getIdentityProviderName() +
                                ", jwks_uri : " + jwksUri);
                    }
                    break;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("JWKS endpoint not specified for the identity provider : " + idp
                                .getIdentityProviderName());
                    }
                }
            }
        }

        if (isJWKSEnabled && hasJWKSUri) {
            JWKSBasedJWTValidator jwksBasedJWTValidator = new JWKSBasedJWTValidator();
            return jwksBasedJWTValidator.validateSignature(signedJWT.getParsedString(), jwksUri, signedJWT.getHeader
                    ().getAlgorithm().getName(), null);
        } else {
            JWSVerifier verifier = null;
            JWSHeader header = signedJWT.getHeader();
            X509Certificate x509Certificate = resolveSignerCertificate(header, idp);
            if (x509Certificate == null) {
                handleException(
                        "Unable to locate certificate for Identity Provider " + idp.getDisplayName() + "; JWT " +
                                header.toString());
            }

            String alg = signedJWT.getHeader().getAlgorithm().getName();
            if (StringUtils.isEmpty(alg)) {
                handleException("Algorithm must not be null.");
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Signature Algorithm found in the JWT Header: " + alg);
                }
                if (alg.startsWith("RS")) {
                    // At this point 'x509Certificate' will never be null.
                    PublicKey publicKey = x509Certificate.getPublicKey();
                    if (publicKey instanceof RSAPublicKey) {
                        verifier = new RSASSAVerifier((RSAPublicKey) publicKey);
                    } else {
                        handleException("Public key is not an RSA public key.");
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Signature Algorithm not supported yet : " + alg);
                    }
                }
                if (verifier == null) {
                    handleException("Could not create a signature verifier for algorithm type: " + alg);
                }
            }

            // At this point 'verifier' will never be null;
            return signedJWT.verify(verifier);
        }
    }

    /**
     * The default implementation resolves one certificate to Identity Provider and ignores the JWT header.
     * Override this method, to resolve and enforce the certificate in any other way
     * such as x5t attribute of the header.
     *
     * @param header The JWT header. Some of the x attributes may provide certificate information.
     * @param idp    The identity provider, if you need it.
     * @return the resolved X509 Certificate, to be used to validate the JWT signature.
     * @throws IdentityOAuth2Exception something goes wrong.
     */
    protected X509Certificate resolveSignerCertificate(JWSHeader header,
                                                       IdentityProvider idp) throws IdentityOAuth2Exception {
        X509Certificate x509Certificate = null;
        try {
            x509Certificate = (X509Certificate) IdentityApplicationManagementUtil
                    .decodeCertificate(idp.getCertificate());
        } catch (CertificateException e) {
            handleException("Error occurred while decoding public certificate of Identity Provider "
                    + idp.getIdentityProviderName() + " for tenant domain " + tenantDomain);
        }
        return x509Certificate;
    }

    protected static void addUserAttributesToCache(OAuth2AccessTokenRespDTO tokenRespDTO, OAuthTokenReqMessageContext
            msgCtx, Map<ClaimMapping, String> userAttributes) {

        AuthorizationGrantCacheKey authorizationGrantCacheKey = new AuthorizationGrantCacheKey(tokenRespDTO
                .getAccessToken());
        AuthorizationGrantCacheEntry authorizationGrantCacheEntry = new AuthorizationGrantCacheEntry(userAttributes);
        authorizationGrantCacheEntry.setSubjectClaim(msgCtx.getAuthorizedUser().getAuthenticatedSubjectIdentifier());

        if (StringUtils.isNotBlank(tokenRespDTO.getTokenId())) {
            authorizationGrantCacheEntry.setTokenId(tokenRespDTO.getTokenId());
        }

        AuthorizationGrantCache.getInstance().addToCacheByToken(authorizationGrantCacheKey,
                authorizationGrantCacheEntry);
    }

    /**
     * Method to validate the claims other than
     * iss - Issuer
     * sub - Subject
     * aud - Audience
     * exp - Expiration Time
     * nbf - Not Before
     * iat - Issued At
     * jti - JWT ID
     * typ - Type
     * <p/>
     * in order to write your own way of validation and use the JWT grant handler,
     * you can extend this class and override this method
     *
     * @param customClaims a map of custom claims
     * @return whether the token is valid based on other claim values
     */
    protected boolean validateCustomClaims(Map< String, Object > customClaims) {
        return true;
    }

    private void handleException(String errorMessage) throws IdentityOAuth2Exception {
        log.error(errorMessage);
        throw new IdentityOAuth2Exception(errorMessage);
    }

    /**
     * To handle exception.
     *
     * @param errorMessage Error Message.
     * @param e            Throwable throwable.
     * @throws IdentityOAuth2Exception Identity Oauth2 Exception.
     */
    private void handleException(String errorMessage, Throwable e) throws IdentityOAuth2Exception {
        log.error(errorMessage, e);
        throw new IdentityOAuth2Exception(errorMessage, e);
    }
}