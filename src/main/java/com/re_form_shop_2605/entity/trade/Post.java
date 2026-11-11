package com.re_form_shop_2605.entity.trade;

import com.re_form_shop_2605.entity.*;
import com.re_form_shop_2605.entity.Enum.*;
import com.re_form_shop_2605.entity.chat.ChatRoom;
import com.re_form_shop_2605.entity.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@ToString(exclude = {"sellerId", "images", "wishes", "chatRooms"}) // 연관 엔티티는 빼서 순환 참조·지연 로딩 예외를 막는다
@NoArgsConstructor
@AllArgsConstructor
@Builder
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-07
 * 설명: 게시글 JPA 엔티티
 * ─────────────────────────────────────────────────────
 */
@EntityListeners(PostStatusListener.class)
public class Post extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_id", nullable = false)
    private Long postId; // 게시글 번호

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "seller_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_post_member")
    )
    private Member sellerId; // 판매자 member id

    @Column(name = "title", length = 200)
    private String title; // 제목

    @Lob
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content; // 본문

    @Enumerated(EnumType.STRING)
    @Column(name = "sport",nullable = false)
    private Sport sport; // 종목

    @Column(name = "team", nullable = false, length = 50)
    private String team; // 구단명

    @Column(name = "uniform_name",length = 200, nullable = false)
    private String uniformName; // 유니폼명

    @Enumerated(EnumType.STRING)
    @Column(name = "grade",nullable = false)
    private Grade grade; // 유니폼 상태 등급

    @Column(name = "size",length = 10)
    private String size; // 유니폼 사이즈 (XS·S·M·L·XL·XXL, UniformSizes.ALLOWED)

    @Column(name = "marking",columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean marking; // 마킹 여부

    @Column(name = "price",nullable = false)
    private int price; // 희망 가격

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type",nullable = false)
    private DeliveryType deliveryType; // 수령 방법

    @Enumerated(EnumType.STRING)
    @Column(name = "status",columnDefinition = "", nullable = false)
    private PostStatus status;

    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private Integer viewCount = 0;

    @Column(name = "wish_count", nullable = false)
    @Builder.Default
    private Integer wishCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level")
    private RiskLevel riskLevel;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "post", fetch = FetchType.LAZY)
    @Builder.Default
    private List<PostImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "post", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Wish> wishes = new ArrayList<>();

    @OneToMany(mappedBy = "post")
    @Builder.Default
    private List<ChatRoom> chatRooms = new ArrayList<>();


    // 판매 상태가 바뀌었는지 - 저장되면 PostStatusListener가 읽고 지운 뒤 이벤트를 발행한다
    @Transient
    private boolean statusChanged;

    public void changePost(String title, String content, Sport sport, String team, String uniformName,
                           Grade grade, String size, Boolean marking, Integer price, DeliveryType deliveryType) {
        if (title != null) {
            this.title = title;
        }
        if (content != null) {
            this.content = content;
        }
        if (sport != null) {
            this.sport = sport;
        }
        if (team != null) {
            this.team = team;
        }
        if (uniformName != null) {
            this.uniformName = uniformName;
        }
        if (grade != null) {
            this.grade = grade;
        }
        if (size != null) {
            this.size = size;
        }
        if (marking != null) {
            this.marking = marking;
        }
        if (price != null) {
            this.price = price;
        }
        if (deliveryType != null) {
            this.deliveryType = deliveryType;
        }
    }

    public void markDeleted() {
        changeStatus(PostStatus.DELETED);
    }

    public void changeStatus(PostStatus status) {
        if (this.status != status) {
            this.statusChanged = true;
        }
        this.status = status;
    }

    /* 판매 상태가 바뀌었으면 true를 돌려주고 표시를 지운다 */
    public boolean consumeStatusChange() {
        boolean changed = statusChanged;
        statusChanged = false;
        return changed;
    }

    public void increaseWishCount() {
        if (this.wishCount == null) {
            this.wishCount = 0;
        }
        this.wishCount++;
    }

    public void decreaseWishCount() {
        if (this.wishCount == null || this.wishCount <= 0) {
            this.wishCount = 0;
            return;
        }
        this.wishCount--;
    }

    public void changeWishCount(int wishCount) {
        this.wishCount = Math.max(wishCount, 0);
    }


    /**
     * 작성자: 손민정
     * 작성일: 2026-05-14
     * 설명: 배치 작업 중 위험 탐지 기준에 걸린 게시물의 riskLevel 업데이트
     */
    public void updateRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }
}
