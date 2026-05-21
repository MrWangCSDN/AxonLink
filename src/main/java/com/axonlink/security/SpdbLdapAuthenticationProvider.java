package com.axonlink.security;

import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.support.DefaultDirObjectFactory;
import org.springframework.ldap.support.LdapUtils;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.ldap.SpringSecurityLdapTemplate;
import org.springframework.security.ldap.authentication.AbstractLdapAuthenticationProvider;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.naming.ldap.InitialLdapContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;

/**
 * ldap域认证，分为以下情况：
 *  用户第一次登陆：将用户信息加入数据库，默认分配普通用户角色，然后查询数据库，进行角色分配
 *  用户曾经登陆过：查询用户角色直接进行分配
 * @author Lee0609x
 */
public class SpdbLdapAuthenticationProvider extends AbstractLdapAuthenticationProvider {

    private String url = "ldap://10.200.63.55:3268";
    private String domain = "hdq.spdb.com";
    private String rootDn;
    private String searchFilter = "(&(objectClass=user)(userPrincipalName={0}))";

    public SpdbLdapAuthenticationProvider() {
        super();
    }

    /**
     * 这里进行认证
     */
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Assert.isInstanceOf(UsernamePasswordAuthenticationToken.class, authentication, messages
                .getMessage("LdapAuthenticationProvider.onlySupports",
                        "Only UsernamePasswordAuthenticationToken is supported"));
        UsernamePasswordAuthenticationToken userToken = (UsernamePasswordAuthenticationToken) authentication;
        String username = userToken.getName();
        String password = (String) authentication.getCredentials();
        if (logger.isDebugEnabled()) {
            logger.debug("Processing authentication request for user: " + username);
        }
        if (!StringUtils.hasLength(username)) {
            throw new BadCredentialsException(
                    messages.getMessage("LdapAuthenticationProvider.emptyUsername", "Empty Username"));
        }
        if (!StringUtils.hasLength(password)) {
            throw new BadCredentialsException(
                    messages.getMessage("AbstractLdapAuthenticationProvider.emptyPassword", "Empty Password"));
        }
        Assert.notNull(password, "Null password was supplied in authentication token");
        DirContextOperations userData = doAuthentication(userToken); // 认证
        if (userData == null) {
            return null;
        }
        UserDetails user = userDetailsContextMapper.mapUserFromContext(userData, authentication.getName(),
                loadUserAuthorities(userData, authentication.getName(), (String) authentication.getCredentials()));
        logger.info("用户: " + authentication.getName() + " 通过域认证进行登陆");
        return createSuccessfulAuthentication(userToken, user);
    }

    @Override
    protected DirContextOperations doAuthentication(UsernamePasswordAuthenticationToken auth) {
        String username = auth.getName();
        String password = (String) auth.getCredentials();
        DirContext ctx = bindAsUser(username, password);
        if (ctx == null) {
            return null;
        }
        try {
            return searchForUser(ctx, username);
        } catch (NamingException e) {
            throw new RuntimeException("Failed to locate directory entry for authenticated user: " + username, e);
        } finally {
            LdapUtils.closeContext(ctx);
        }
    }

    @Override
    protected Collection<? extends GrantedAuthority> loadUserAuthorities(DirContextOperations userData, String username,
                                                                        String password) {
        return new ArrayList<>();
    }

    /**
     * ldap 连接
     * @param username
     * @param password
     * @return
     */
    private DirContext bindAsUser(String username, String password) {
        String bindUrl = url;
        Hashtable<String, String> env = new Hashtable<String, String>();
        env.put("java.naming.security.authentication", "simple");
        String bindPrincipal = createBindPrincipal(username);
        env.put("java.naming.security.principal", bindPrincipal);
        env.put("java.naming.provider.url", bindUrl);
        env.put("java.naming.security.credentials", password);
        env.put("java.naming.factory.initial", "com.sun.jndi.ldap.LdapCtxFactory");
        env.put("java.naming.factory.object", DefaultDirObjectFactory.class.getName());
        try {
            return new InitialLdapContext(env, null);
        } catch (NamingException e) {
            e.printStackTrace();
            logger.warn("LDAP:[" + domain + "]登陆认证失败，该用户不是该域用户/密码错误");
            return null;
//          throw new SLdapNamingException("LDAP:[" + domain + "]登陆认证失败，该用户不是该域用户/密码错误", e);
        }
    }

    /**
     * 查询
     * @param context
     * @param username
     * @return
     * @throws NamingException
     */
    private DirContextOperations searchForUser(DirContext context, String username) throws NamingException {
        SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(2);
        String bindPrincipal = createBindPrincipal(username);
        String searchRoot = rootDn != null ? rootDn : searchRootFromPrincipal(bindPrincipal);
        try {
            return SpringSecurityLdapTemplate.searchForSingleEntryInternal(context, searchControls, searchRoot,
                    searchFilter, new Object[] { bindPrincipal, username });
        } catch (IncorrectResultSizeDataAccessException incorrectResults) {
            if (incorrectResults.getActualSize() != 0) {
                throw incorrectResults;
            }
            UsernameNotFoundException userNameNotFoundException = new UsernameNotFoundException(
                    "User " + username + " not found in directory.", incorrectResults);
            throw new RuntimeException(userNameNotFoundException);
        }
    }

    private String createBindPrincipal(String username) {
        if ((domain == null) || (username.toLowerCase().endsWith(domain))) {
            return username;
        }
        return username + "@" + domain;
    }

    private String searchRootFromPrincipal(String bindPrincipal) {
        int atChar = bindPrincipal.lastIndexOf('@');
        if (atChar < 0) {
            throw new RuntimeException(new BadCredentialsException("User principal '" + bindPrincipal
                    + "' does not contain the domain, and no domain has been configured"));
        }
        return rootDnFromDomain(bindPrincipal.substring(atChar + 1, bindPrincipal.length()));
    }

    private String rootDnFromDomain(String domain) {
        String[] tokens = StringUtils.tokenizeToStringArray(domain, ".");
        StringBuilder root = new StringBuilder();
        for (String token : tokens) {
            if (root.length() > 0) {
                root.append(',');
            }
            root.append("dc=").append(token);
        }
        return root.toString();
    }
}
