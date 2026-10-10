package com.stockmarketanalyzer.backend.admin;

import com.stockmarketanalyzer.backend.admin.web.dto.AdminUserResponse;
import com.stockmarketanalyzer.backend.admin.web.dto.CreateUserRequest;
import com.stockmarketanalyzer.backend.admin.web.dto.RealmRoleResponse;
import com.stockmarketanalyzer.backend.admin.web.dto.ResetPasswordRequest;
import com.stockmarketanalyzer.backend.common.PageResponse;
import com.stockmarketanalyzer.backend.security.CurrentUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * User administration, expressed in terms of Keycloak underneath.
 *
 * <p>The scope is deliberately narrow: create an account, replace its password, grant and revoke
 * privileges, switch it off. There is no delete, and that is a data decision rather than a
 * timidity one -- wealth entries and dividends are owned by the Keycloak subject, and a deleted
 * user leaves rows behind that nothing can ever re-attach to. Disabling revokes every sign-in
 * and keeps the history intact, which is what "remove someone's access" almost always means.
 *
 * <p>Two things this class refuses to do, both guarding the same failure: an administrator
 * locking themselves out of the only screen that could undo it. You cannot disable your own
 * account, and you cannot take {@code ADMIN} away from yourself. Somebody else with the role
 * still can, which is the difference between a safety rail and a policy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final String ADMIN_ROLE = "ADMIN";

    /**
     * Keycloak's own realm roles. They are OIDC and authorization-services machinery rather than
     * privileges of this application, and listing them as choices would sooner or later get one
     * of them revoked from somebody -- which breaks refresh tokens, not permissions.
     */
    private static final Set<String> BUILT_IN_ROLES = Set.of("offline_access", "uma_authorization");

    private static final String DEFAULT_ROLES_PREFIX = "default-roles-";

    /**
     * Absent under the {@code local} profile, exactly as {@code KeycloakTokenClient} is in
     * {@code AuthService}. An {@link ObjectProvider} rather than an optional autowire so the
     * absence is a case this class answers, not a null it must remember to check.
     */
    private final ObjectProvider<KeycloakAdminClient> keycloak;

    private final CurrentUser currentUser;

    // ---------------------------------------------------------------- queries

    /**
     * A page of users.
     *
     * <p>Each row costs one extra call for its role mappings, because Keycloak's user list does
     * not carry them. That is N+1 by construction and is left as it is on purpose: the page size
     * is capped in the controller, this is a screen one person looks at occasionally, and the
     * alternative -- reading every role's membership and inverting it -- is more requests, not
     * fewer, for any realm with more roles than users on a page.
     */
    public PageResponse<AdminUserResponse> list(String search, int page, int size) {
        KeycloakAdminClient client = client();
        List<Map<String, Object>> users = client.searchUsers(search, page * size, size);
        long total = client.countUsers(search);

        List<AdminUserResponse> content = new ArrayList<>(users.size());
        for (Map<String, Object> user : users) {
            content.add(toResponse(user, rolesOf(client, id(user))));
        }

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new PageResponse<>(
                content, page, size, total, totalPages, page == 0, page >= totalPages - 1);
    }

    public AdminUserResponse get(String id) {
        KeycloakAdminClient client = client();
        return toResponse(client.getUser(id), rolesOf(client, id));
    }

    /** The privileges an administrator may hand out, read from the realm rather than hard-coded. */
    public List<RealmRoleResponse> assignableRoles() {
        return client().realmRoles().stream()
                .filter(role -> isAssignable(name(role)))
                .map(role -> new RealmRoleResponse(name(role), text(role.get("description"))))
                .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
                .toList();
    }

    // ---------------------------------------------------------------- commands

    /**
     * Creates the account and grants its roles.
     *
     * <p>The password rides along in the creation representation, so there is no moment at which
     * the account exists with no way to sign in to it. The roles cannot: Keycloak ignores
     * {@code realmRoles} on {@code POST /users} (it is honoured only by realm import), so they
     * are a second call. A failure there therefore leaves a real account with no privileges --
     * recoverable from this very screen, and the reason the response is re-read from Keycloak
     * rather than echoed back from the request.
     */
    public AdminUserResponse create(CreateUserRequest request) {
        KeycloakAdminClient client = client();
        List<String> requested = validateRoles(request.rolesOrEmpty());

        Map<String, Object> representation = new LinkedHashMap<>();
        representation.put("username", request.username().trim());
        representation.put("enabled", true);
        putIfPresent(representation, "email", request.email());
        putIfPresent(representation, "firstName", request.firstName());
        putIfPresent(representation, "lastName", request.lastName());
        // Marked verified rather than left pending: this deployment has no mail transport, so a
        // verification email would never arrive and the account would be unusable.
        if (request.email() != null && !request.email().isBlank()) {
            representation.put("emailVerified", true);
        }
        representation.put(
                "credentials",
                List.of(Map.of(
                        "type", "password",
                        "value", request.password(),
                        "temporary", request.temporaryPassword())));

        String id = client.createUser(representation);
        log.info("Created user '{}' ({})", request.username(), id);

        if (!requested.isEmpty()) {
            client.addRealmRoles(id, roleRepresentations(client, requested));
        }
        return get(id);
    }

    /** Replaces the password. The old one is neither needed nor knowable. */
    public void resetPassword(String id, ResetPasswordRequest request) {
        KeycloakAdminClient client = client();
        client.getUser(id); // 404 before the write, so a bad id is not reported as a failed reset
        client.resetPassword(id, request.password(), request.temporary());
        log.info("Reset the password of user {}", id);
    }

    public AdminUserResponse setEnabled(String id, boolean enabled) {
        if (!enabled && isSelf(id)) {
            throw new IllegalArgumentException(
                    "You cannot disable your own account. Ask another administrator to do it.");
        }
        KeycloakAdminClient client = client();
        client.updateUser(id, Map.of("enabled", enabled));
        log.info("{} user {}", enabled ? "Enabled" : "Disabled", id);
        return get(id);
    }

    /**
     * Makes the user's realm roles match the requested set exactly.
     *
     * <p>Diffed against what Keycloak currently holds rather than applied wholesale: re-granting
     * a role the user already has is a write Keycloak records, and revoking-then-granting the
     * same role would briefly leave an administrator without it.
     */
    public AdminUserResponse setRoles(String id, List<String> roles) {
        KeycloakAdminClient client = client();
        Set<String> requested = new LinkedHashSet<>(validateRoles(roles));

        if (isSelf(id) && !requested.contains(ADMIN_ROLE)) {
            throw new IllegalArgumentException(
                    "You cannot take the ADMIN role away from yourself. Ask another administrator to do it.");
        }

        List<Map<String, Object>> current = client.userRealmRoles(id);
        Set<String> held =
                current.stream().map(UserAdminService::name).collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> toAdd = requested.stream()
                .filter(role -> !held.contains(role))
                .toList();
        // Only assignable roles are ever taken away: a built-in Keycloak role the user happens to
        // hold is not on the screen, so its absence from the request means "not shown", not "no".
        List<String> toRemove = held.stream()
                .filter(role -> !requested.contains(role))
                .filter(UserAdminService::isAssignable)
                .toList();

        if (!toAdd.isEmpty()) {
            client.addRealmRoles(id, roleRepresentations(client, toAdd));
        }
        if (!toRemove.isEmpty()) {
            List<Map<String, Object>> removals = current.stream()
                    .filter(role -> toRemove.contains(name(role)))
                    .toList();
            client.removeRealmRoles(id, removals);
        }
        if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
            log.info("User {} roles: granted {}, revoked {}", id, toAdd, toRemove);
        }
        return get(id);
    }

    // ---------------------------------------------------------------- plumbing

    private KeycloakAdminClient client() {
        KeycloakAdminClient client = keycloak.getIfAvailable();
        if (client == null) {
            throw new UserDirectoryUnavailableException(
                    "User administration needs Keycloak, and this instance is running on the 'local' profile "
                            + "with the security chain replaced by a fixed developer identity.",
                    null);
        }
        return client;
    }

    /** True when the id is the caller's own Keycloak subject. */
    private boolean isSelf(String id) {
        try {
            return currentUser.requireSubject().equals(id);
        } catch (IllegalStateException ex) {
            // No JWT on the context. Unreachable behind the ADMIN rule, and answering "not you"
            // only ever makes the guards below stricter about nothing.
            return false;
        }
    }

    /**
     * Rejects a role the realm does not offer before anything is written, so an administrator
     * gets "no such role" rather than a half-applied change and a 400 from Keycloak.
     */
    private List<String> validateRoles(List<String> requested) {
        if (requested.isEmpty()) {
            return List.of();
        }
        Set<String> offered = assignableRoles().stream()
                .map(RealmRoleResponse::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> unknown = requested.stream()
                .filter(role -> !offered.contains(role))
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("No such realm role: " + String.join(", ", unknown));
        }
        return requested.stream().distinct().toList();
    }

    /**
     * Keycloak's role-mapping endpoints want whole role representations, id included -- a list of
     * names is rejected. They are looked up rather than constructed, because the id is Keycloak's
     * and nothing here can invent it.
     */
    private List<Map<String, Object>> roleRepresentations(KeycloakAdminClient client, List<String> names) {
        return client.realmRoles().stream()
                .filter(role -> names.contains(name(role)))
                .toList();
    }

    private static List<String> rolesOf(KeycloakAdminClient client, String id) {
        return client.userRealmRoles(id).stream()
                .map(UserAdminService::name)
                .filter(UserAdminService::isAssignable)
                .sorted(String::compareToIgnoreCase)
                .toList();
    }

    private static boolean isAssignable(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        String lowered = roleName.toLowerCase(Locale.ROOT);
        return !lowered.startsWith(DEFAULT_ROLES_PREFIX) && !BUILT_IN_ROLES.contains(lowered);
    }

    private static AdminUserResponse toResponse(Map<String, Object> user, List<String> roles) {
        Object created = user.get("createdTimestamp");
        return new AdminUserResponse(
                id(user),
                text(user.get("username")),
                text(user.get("email")),
                text(user.get("firstName")),
                text(user.get("lastName")),
                !Boolean.FALSE.equals(user.get("enabled")),
                roles,
                created instanceof Number millis ? Instant.ofEpochMilli(millis.longValue()) : null);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private static String id(Map<String, Object> representation) {
        return text(representation.get("id"));
    }

    private static String name(Map<String, Object> representation) {
        return text(representation.get("name"));
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }
}
