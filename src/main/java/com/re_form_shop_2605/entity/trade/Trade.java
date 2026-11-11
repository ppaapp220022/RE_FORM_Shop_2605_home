package com.re_form_shop_2605.entity.trade;

import com.re_form_shop_2605.entity.BaseEntity;
import com.re_form_shop_2605.entity.Enum.TradeDeliveryType;
import com.re_form_shop_2605.entity.Enum.TradeStatus;
import com.re_form_shop_2605.entity.chat.ChatRoom;
import com.re_form_shop_2605.entity.trade.MannerReview;
import com.re_form_shop_2605.entity.member.Member;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@ToString(exclude = {"post", "buyer", "seller", "chatRooms"}) // 연관 엔티티는 빼서 순환 참조·지연 로딩 예외를 막는다
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "trade")
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-24
 * 설명: 거래 진행 상태와 배송 정보를 관리하는 JPA 엔티티
 * ─────────────────────────────────────────────────────
 */
public class Trade extends BaseEntity {

    // 거래 번호
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trade_id", nullable = false)
    private Long tradeId;

    // 연결 판매글
    // 판매글 하나에 거래가 여러 번 생길 수 있다(취소 뒤 다시 거래). 진행 중인 거래는 하나만 허용하며 서비스에서 검사한다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "post_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_trade_post")
    )
    private Post post;

    // 구매자 회원
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "buyer_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_trade_buyer_member")
    )
    private Member buyer;

    // 판매자 회원
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "seller_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_trade_seller_member")
    )
    private Member seller;

    // 거래 상태
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TradeStatus status;

    // 배송 방식
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type")
    private TradeDeliveryType deliveryType;

    // 배송지
    @Column(name = "delivery_address", length = 300)
    private String deliveryAddress;

    public void changeDeliveryAddress(String deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    // 택배사 코드
    @Column(name = "courier_code", length = 50)
    private String courierCode;

    // 택배사명
    @Column(name = "courier_name", length = 100)
    private String courierName;

    // 송장번호
    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    // 배송 시작 시각
    @Column(name = "shipping_started_at")
    private LocalDateTime shippingStartedAt;

    // 거래 금액
    @Column(name = "trade_price", nullable = false)
    private Integer tradePrice;

    // 거래 완료 시각
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // 구매 확정 시각
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    // 연결 채팅방 목록
    @OneToMany(mappedBy = "trade")
    @Builder.Default
    private List<ChatRoom> chatRooms = new ArrayList<>();

//    @OneToOne(mappedBy = "trade", fetch = FetchType.LAZY)
//    private MannerReview mannerReview;

    // 수령 완료 시각
    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    public void accept() {
        validateCurrentStatus(TradeStatus.REQUESTED, "구매 요청 상태의 거래만 수락할 수 있습니다.");
        this.status = TradeStatus.ACCEPTED;
    }

    public void markPaid() {
        validateCurrentStatus(TradeStatus.ACCEPTED, "수락된 거래만 결제 완료 처리할 수 있습니다.");
        this.status = TradeStatus.PAID;
    }

    public void beginShippingProgress() {
        validateCurrentStatus(TradeStatus.PAID, "결제 완료된 거래만 배송 진행 상태로 전환할 수 있습니다.");
        this.status = TradeStatus.IN_PROGRESS;
    }

    public void updateShippingInfo(String courierCode, String courierName, String trackingNumber) {
        if (this.status != TradeStatus.IN_PROGRESS) {
            throw new IllegalStateException("배송 진행 중인 거래만 송장 정보를 입력할 수 있습니다.");
        }
        this.courierCode = courierCode;
        this.courierName = courierName;
        this.trackingNumber = trackingNumber;
        if (this.shippingStartedAt == null) {
            this.shippingStartedAt = LocalDateTime.now();
        }
    }

    public boolean hasShippingInfo() {
        return courierCode != null && !courierCode.isBlank()
                && trackingNumber != null && !trackingNumber.isBlank();
    }

    public void confirm() {
        boolean directTradeConfirmable = this.deliveryType == TradeDeliveryType.DIRECT
                && this.status == TradeStatus.ACCEPTED;
        if (!directTradeConfirmable
                && this.status != TradeStatus.PAID
                && this.status != TradeStatus.IN_PROGRESS
                && this.status != TradeStatus.RECEIVED) {
            throw new IllegalStateException("구매 확정이 가능한 거래 상태가 아닙니다.");
        }
        this.status = TradeStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    public void complete() {
        validateCurrentStatus(TradeStatus.CONFIRMED, "구매 확정된 거래만 정산 완료 처리할 수 있습니다.");
        this.status = TradeStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (this.status == TradeStatus.CONFIRMED || this.status == TradeStatus.COMPLETED) {
            throw new IllegalStateException("구매 확정 이후 거래는 취소할 수 없습니다.");
        }
        this.status = TradeStatus.CANCELED;
    }

    public void resolveDispute(TradeStatus status) {
        if (status != TradeStatus.DISPUTED
                && status != TradeStatus.CONFIRMED
                && status != TradeStatus.CANCELED) {
            throw new IllegalArgumentException("분쟁 처리 가능한 상태가 아닙니다.");
        }
        this.status = status;
        if (status == TradeStatus.CONFIRMED && this.confirmedAt == null) {
            this.confirmedAt = LocalDateTime.now();
        }
    }

    private void validateCurrentStatus(TradeStatus expected, String message) {
        if (this.status != expected) {
            throw new IllegalStateException(message);
        }
    }
}
