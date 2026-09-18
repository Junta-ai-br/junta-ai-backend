package br.com.juntaai.security;

import br.com.juntaai.entity.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Principal customizado. Carrega o UUID do usuário direto no contexto de
 * segurança para que controllers/services não precisem consultar o banco
 * de novo só para saber "quem é o usuário logado".
 *
 * Não há mais senha local (login é por Google ou código de e-mail) —
 * getPassword() existe só porque a interface UserDetails exige, e nunca é
 * usado para autenticar nada.
 */
public class AuthenticatedUser implements UserDetails {

    private final UUID id;
    private final String email;
    private final Role role;

    public AuthenticatedUser(UUID id, String email, Role role) {
        this.id = id;
        this.email = email;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
