package com.wesabe.bouncer.auth;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import net.spy.memcached.MemcachedClientIF;

import org.apache.commons.codec.binary.Base64;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Authentication.User;

/**
 * An {@link Authenticator} which, given a {@link DataSource} for the PFC
 * database, authenticated Basic HTTP Authorization requests against the PFC
 * user records and returns {@link WesabeCredentials}.
 * 
 * @author coda
 *
 */
public class WesabeAuthenticator implements org.eclipse.jetty.security.Authenticator {
    private static class AuthHeader {
        private static final String BASIC_AUTHENTICATION_PREFIX = "Basic ";
        private final String username, password;
        
        public static AuthHeader parse(String authHeader) {
                if ((authHeader != null) && authHeader.startsWith(BASIC_AUTHENTICATION_PREFIX)) {
                        final String encodedCreds = authHeader.substring(BASIC_AUTHENTICATION_PREFIX.length(), authHeader.length());
                        final String creds = new String(Base64.decodeBase64(encodedCreds.getBytes()));
                        int separator = creds.indexOf(':');
                        if (separator > 0) {
                                final String username = creds.substring(0, separator);
                                final String password = creds.substring(separator + 1);
                                
                                return new AuthHeader(username, password);
                        }
                }
                
                return null;
        }
        
        public AuthHeader(String username, String password) {
                this.username = username;
                this.password = password;
        }
        
        public String getUsername() {
                return username;
        }
        
        public String getPassword() {
                return password;
        }
    }

    private static class UserRecord {
		private static final String USER_ID_FIELD = "id";
		private static final String UID_FIELD = "uid";
		private static final String SALT_FIELD = "salt";
		private static final String PASSWORD_HASH_FIELD = "password_hash";
		private final int userId;
		private final String uid, salt, passwordHash;
		
		public UserRecord(ResultSet resultSet) throws SQLException {
			this.salt = resultSet.getString(SALT_FIELD);
			this.userId = resultSet.getInt(USER_ID_FIELD);
			this.uid = resultSet.getString(UID_FIELD);
			this.passwordHash = resultSet.getString(PASSWORD_HASH_FIELD);
		}
	}
	
	private static final Logger LOGGER = Logger.getLogger(WesabeAuthenticator.class.getCanonicalName());
	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String USER_SELECT_SQL =
		"SELECT * FROM (" +
				"SELECT id, uid, salt, password_hash, last_web_login " +
				"FROM users " +
				"WHERE (username = ?) AND status IN (0, 6) " + // 0 is ACTIVE, 6 is PENDING
			"UNION " +
				"SELECT id, uid, salt, password_hash, last_web_login " +
				"FROM users " +
				"WHERE (email = ?) AND status IN (0, 6)" + // 0 is ACTIVE, 6 is PENDING
		") AS t " +
		"ORDER BY last_web_login DESC " +
		"LIMIT 1";
	private static final int ONE_DAY = 60 * 60 * 24;
	private final DataSource dataSource;
	private final MemcachedClientIF memcached;
	private final PasswordHasher hasher = new PasswordHasher();
	private final String realm;
	
	public WesabeAuthenticator(String realm, DataSource dataSource, MemcachedClientIF memcached) {
		this.dataSource = dataSource;
		this.memcached = memcached;
		this.realm = realm;
	}
	
	private WesabeCredentials buildCredentials(AuthHeader header, UserRecord user)
			throws LockedAccountException, BadCredentialsException {
		if (isThrottled(user.userId)) {
			final int penalty = registerFailedLogin(user.userId);
			throw new LockedAccountException(penalty);
		}
		
		if (user.passwordHash.equals(hasher.getPasswordHash(header.getPassword(), user.salt))) {
			registerSuccessfulLogin(user.userId);
			return new WesabeCredentials(
					user.userId,
					hasher.getAccountKey(user.uid, header.getPassword())
			);
		}
		
		final int penalty = registerFailedLogin(user.userId);
		if (penalty > 0) {
			throw new LockedAccountException(penalty);
		}
		
		throw new BadCredentialsException();
	}
	
	private int getPenalty(long failedAttempts) {
	    final int freeLoginAttempts = 3;
	    if (failedAttempts <= freeLoginAttempts) {
			return 0;
		}
	    final int initialPenalty = 15; // seconds
	    final int maxPenalty = 60 * 15; // 15 minutes
	    final double unfreeAttempts = failedAttempts - freeLoginAttempts - 1;
	    final double penalty = Math.pow(2, unfreeAttempts) * initialPenalty;
	    return Double.valueOf(Math.min(penalty, maxPenalty)).intValue();
	}

	
	private String accountCounterKey(int userId) {
		final StringBuilder builder = new StringBuilder();
		builder.append("failed-logins:");
		builder.append(userId);
		return builder.toString();
	}
	
	private int registerFailedLogin(int userId) {
		final String key = accountCounterKey(userId);
		memcached.add(key, ONE_DAY, Integer.valueOf(0));
		final long failedLogins = memcached.incr(key, 1);
		final int penalty = getPenalty(failedLogins);
		if (penalty > 0) {
			lockAccount(userId, penalty);
		}
		return penalty;
	}
	
	private void registerSuccessfulLogin(int userId) {
		memcached.delete(accountCounterKey(userId));
	}

	private void lockAccount(int userId, int penalty) {
		LOGGER.info("Locking user " + userId + " for " + penalty + " seconds");
		memcached.set(accountLockKey(userId), penalty, Integer.valueOf(penalty));
	}

	private String accountLockKey(int userId) {
		final StringBuilder builder = new StringBuilder();
		builder.append("lock-account:");
		builder.append(userId);
		return builder.toString();
	}
	
	private boolean isThrottled(int userId) {
		return memcached.get(accountLockKey(userId)) != null;
	}

	private UserRecord getUserRecord(Connection connection, AuthHeader header)
			throws SQLException {
		final PreparedStatement statement = connection.prepareStatement(USER_SELECT_SQL);
		try {
			statement.setString(1, header.getUsername());
			statement.setString(2, header.getUsername());
			final ResultSet resultSet = statement.executeQuery();
			try {
				if (resultSet.first()) {
					return new UserRecord(resultSet);
				}
			} finally {
				resultSet.close();
			}
			return null;
		} finally {
			statement.close();
		}
	}

	@Override
	public String getAuthMethod() {
		return Constraint.__BASIC_AUTH;
	}

	@Override
	public boolean secureResponse(ServletRequest request, ServletResponse response, boolean mandatory, User validatedUser)
			throws ServerAuthException {
		return true;
	}

	@Override
	public void setConfiguration(Configuration configuration) {
		// nothing to do
	}

	@Override
	public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory)
			throws ServerAuthException {
		final HttpServletResponse httpResponse = (HttpServletResponse) response;
		
        try {
			try {
				return new UserAuthentication(this, new WesabeUserIdentity( authenticate(request) ));
			} catch (BadCredentialsException e) {
		        httpResponse.setHeader(HttpHeaders.WWW_AUTHENTICATE, "basic realm=\"" + getRealm() + '"');
				httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED);
				return Authentication.SEND_CONTINUE;
			} catch (LockedAccountException e) {
				httpResponse.setIntHeader(HttpHeaders.RETRY_AFTER, e.getPenaltyDuration());
				httpResponse.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return Authentication.SEND_FAILURE;
			}
		} catch (IOException e1) {
			throw new ServerAuthException(e1);
		}
	}
	
	public WesabeCredentials authenticate(ServletRequest request) throws BadCredentialsException, LockedAccountException {
		final HttpServletRequest httpRequest = (HttpServletRequest) request;
		AuthHeader header = AuthHeader.parse(httpRequest.getHeader(AUTHORIZATION_HEADER));
		
		if (header != null) {
			try {
				final Connection connection = dataSource.getConnection();
				try {
					final UserRecord user = getUserRecord(connection, header);
					if (user != null)
						return buildCredentials(header, user);
				} finally {
					connection.close();
				}

			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}
		
		throw new BadCredentialsException();
	}
	
	public String getRealm() {
		return realm;
	}
}
