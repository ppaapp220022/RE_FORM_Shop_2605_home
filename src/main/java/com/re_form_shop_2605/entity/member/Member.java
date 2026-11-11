package com.re_form_shop_2605.entity.member;

import com.re_form_shop_2605.entity.BaseEntity;
import com.re_form_shop_2605.entity.Enum.MemberStatus;
import com.re_form_shop_2605.entity.Enum.Role;
import com.re_form_shop_2605.entity.chat.ChatMessage;
import com.re_form_shop_2605.entity.chat.ChatRoom;
import com.re_form_shop_2605.entity.community.CommunityPost;
import com.re_form_shop_2605.entity.community.Reply;
import jakarta.persistence.*;
import lombok.*;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@ToString(exclude = {"socialMembers", "interestKeywords", "chatMessages", "chatRooms", "communityPosts", "replies", "interestSetting"}) // 연관 엔티티는 빼서 순환 참조·지연 로딩 예외를 막는다
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "member")
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-07
 * 설명: 회원 JPA 엔티티
 * ─────────────────────────────────────────────────────
 */
public class Member extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id", nullable = false)
    private Long memberId; // 회원 id

    @Column(name = "email", nullable = false, unique = true,  length = 100)
    private String email; // 회원 이메일

    @Column(name = "password", length = 255)
    private String password; // 회원 비밀번호

    @Column(name = "nickname", nullable = false, unique = true,  length = 50)
    private String nickname; // 회원 닉네임

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl; // 회원 프로필 이미지 url

    @Column(name = "bio", length = 200)
    private String bio; // 회원 자기소개

    @Column(name = "manner_score",  nullable = false, precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal mannerScore = BigDecimal.valueOf(0.00); // 매너 평균 점수

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER; // 사용자 분류(유저, 관리자)

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MemberStatus status = MemberStatus.ACTIVE; // 활동상태 (활성화/정지/탈퇴)

    @Setter
    @Column(name = "warning_count" , nullable = false)
    @Builder.Default
    private int warningCount = 0; // 누적 경고 횟수

    @Column(name = "email_event", nullable = false)
    private boolean emailEvent; // 이벤트 이메일 수신 여부

    @OneToMany(mappedBy = "member", fetch = FetchType.LAZY)
    @Builder.Default
    private List<SocialMember> socialMembers = new ArrayList<>();

    @OneToMany(mappedBy = "member", fetch = FetchType.LAZY)
    @Builder.Default
    private List<InterestKeyword> interestKeywords = new ArrayList<>();

    @OneToMany(mappedBy = "member")
    @Builder.Default
    private List<ChatMessage> chatMessages = new ArrayList<>();

    @OneToMany(mappedBy = "buyer")
    @Builder.Default
    private List<ChatRoom> chatRooms = new ArrayList<>();

    @OneToMany(mappedBy = "member")
    @Builder.Default
    private List<CommunityPost> communityPosts = new ArrayList<>();

    @OneToMany(mappedBy = "member")
    @Builder.Default
    private List<Reply> replies = new ArrayList<>();

    @OneToOne(mappedBy = "member", fetch = FetchType.LAZY)
    private InterestSetting interestSetting;

    public void setPassword(@Nullable String encode) {
        this.password = encode;
    }

    public void changeProfile(
            String nickname, String profileImageUrl, String bio
    ) {
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.bio = bio;
    }

    public void changeEmailEvent(boolean emailEvent) {
        this.emailEvent = emailEvent;
    }

    public void updateMannerScore(BigDecimal mannerScore) {
        this.mannerScore = mannerScore;
    }
}
