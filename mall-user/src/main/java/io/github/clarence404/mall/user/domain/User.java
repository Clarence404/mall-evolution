package io.github.clarence404.mall.user.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class User {

    public static final int ACTIVE = 1;

    private long id;
    private String phone;
    private String passwordHash;
    private String nickname;
    private int status;
}
