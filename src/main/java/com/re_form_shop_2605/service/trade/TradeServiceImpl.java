package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.dto.common.PageResponse;
import com.re_form_shop_2605.dto.delivery.DeliveryTrackingTraceRequestDTO;
import com.re_form_shop_2605.dto.delivery.DeliveryTrackingTraceResponseDTO;
import com.re_form_shop_2605.dto.etc.TradeNotificationTemplateDTO;
import com.re_form_shop_2605.dto.trade.DeliveryRequestDTO;
import com.re_form_shop_2605.dto.member.MemberBriefDTO;
import com.re_form_shop_2605.dto.chat.PostBriefDTO;
import com.re_form_shop_2605.dto.trade.ReviewRequestDTO;
import com.re_form_shop_2605.dto.trade.ReviewResponseDTO;
import com.re_form_shop_2605.dto.trade.TradeRequestDTO;
import com.re_form_shop_2605.dto.trade.TradeResponseDTO;
import com.re_form_shop_2605.dto.trade.TradeShippingRequestDTO;
import com.re_form_shop_2605.entity.Enum.DeliveryType;
import com.re_form_shop_2605.entity.Enum.PostStatus;
import com.re_form_shop_2605.entity.Enum.TradeDeliveryType;
import com.re_form_shop_2605.entity.Enum.TradeStatus;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.entity.trade.MannerReview;
import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.entity.trade.PostImage;
import com.re_form_shop_2605.entity.trade.Trade;
import com.re_form_shop_2605.repository.member.MemberRepository;
import com.re_form_shop_2605.repository.trade.PostRepository;
import com.re_form_shop_2605.repository.trade.TradeRepository;
import com.re_form_shop_2605.repository.trade.mannerReviewRepository;
import com.re_form_shop_2605.repository.trade.postImageRepository;
import com.re_form_shop_2605.service.common.ServicePageResponse;
import com.re_form_shop_2605.service.delivery.DeliveryTrackingService;
import com.re_form_shop_2605.service.chat.ChatService;
import com.re_form_shop_2605.service.etc.NotificationService;
import com.re_form_shop_2605.service.mail.MailService;
import com.re_form_shop_2605.entity.Enum.PointHistoryType;
import com.re_form_shop_2605.entity.payment.PointHistory;
import com.re_form_shop_2605.entity.payment.PointWallet;
import com.re_form_shop_2605.repository.payment.PointHistoryRepository;
import com.re_form_shop_2605.repository.payment.PointWalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-10
 * 설명: 거래 관련 서비스 구현체
 * ─────────────────────────────────────────────────────
 */
// 거래 생성, 상태 변경, 리뷰 작성 같은 거래 흐름을 담당한다.
@Service
@Log4j2
@RequiredArgsConstructor
@Transactional
public class TradeServiceImpl implements TradeService {

    private static final int REVIEW_WRITE_PERIOD_DAYS = 5;

    private final TradeRepository tradeRepository;
    private final MemberRepository memberRepository;
    private final PostRepository postRepository;
    private final postImageRepository postImageRepository;
    private final mannerReviewRepository mannerReviewRepository;
    private final DeliveryTrackingService deliveryTrackingService;
    private final NotificationService notificationService;
    private final MailService mailService;
    private final ChatService chatService; // 거래 이벤트 채팅방 시스템 메시지 발송용
    private final TradeRealtimeEventService tradeRealtimeEventService;
    private final PointWalletRepository pointWalletRepository; // 구매 확정 시 판매자 포인트 정산용
    private final PointHistoryRepository pointHistoryRepository; // 포인트 이력 기록용
    private final PostImageService postImageService;

    @Override
    // 판매글 상태와 전달 방식 조건을 검증한 뒤 거래를 생성한다.
    public Long addTrade(Long buyerId, TradeRequestDTO tradeRequestDTO) {
        Member buyer = memberRepository.findById(buyerId)
                .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));
        // 판매글 행을 잠근 채로 상태·진행 중 거래를 확인해야 동시 요청이 둘 다 통과하지 않는다.
        Post post = postRepository.findByIdForUpdate(tradeRequestDTO.postId())
                .orElseThrow(() -> new IllegalArgumentException("판매글이 존재하지 않습니다."));

        if (post.getSellerId().getMemberId().equals(buyerId)) {
            throw new IllegalArgumentException("본인 판매글에는 거래를 생성할 수 없습니다.");
        }

        if (post.getStatus() != PostStatus.ON_SALE) {
            throw new IllegalArgumentException("판매 중인 게시글만 거래를 생성할 수 있습니다.");
        }

        // 취소된 거래는 이력으로 남겨 두고 새 거래를 만든다(결제·채팅 기록이 거래별로 남는다).
        if (tradeRepository.existsByPost_PostIdAndStatusNot(post.getPostId(), TradeStatus.CANCELED)) {
            throw new IllegalArgumentException("이미 거래가 진행 중인 게시글입니다.");
        }

        Trade trade = Trade.builder()
                .post(post)
                .buyer(buyer)
                .seller(post.getSellerId())
                .status(TradeStatus.REQUESTED)
                .deliveryType(resolveTradeDeliveryType(post.getDeliveryType(), tradeRequestDTO.deliveryType()))
                .tradePrice(post.getPrice())
                .build();

        trade = tradeRepository.save(trade);
        tradeRealtimeEventService.ensureTradeChatRoom(trade);

        // 거래 생성 시 채팅방 자동 생성 및 안내 메시지 발송
        chatService.sendSystemMessage(
                post.getPostId(), buyer.getMemberId(),
                "[RE:FORM] 구매 요청이 시작되었습니다. 판매자의 수락을 기다리고 있습니다."
        );
        TradeNotificationTemplateDTO sellerCreatedTemplate = notificationService.buildSellerTradeCreatedTemplate(
                trade.getTradeId(),
                trade.getPost().getTitle()
        );
        sendTradeNotice(trade.getSeller(), sellerCreatedTemplate);

        tradeRealtimeEventService.publishTradeUpdated(trade, "TRADE_CREATED");

        return trade.getTradeId();
    }

    @Override
    @Transactional(readOnly = true)
    // 거래 상세 화면에 필요한 정보를 조립해 반환한다.
    public TradeResponseDTO readTrade(Long tradeId) {
        return mapTradeResponse(getTrade(tradeId));
    }

    @Override
    @Transactional(readOnly = true)
    public TradeResponseDTO readTrade(Long requesterId, Long tradeId) {
        Trade trade = getTrade(tradeId);
        if (!isTradeParticipant(trade, requesterId)) {
            throw new IllegalArgumentException("거래 참여자만 거래 상세를 조회할 수 있습니다.");
        }
        return mapTradeResponse(trade);
    }

    @Override
    @Transactional(readOnly = true)
    // 구매자 기준 거래 목록을 상태 필터와 함께 페이지 단위로 반환한다.
    // status != null 이면 DB 레벨에서 필터링하여 불필요한 전체 목록 로드를 방지한다.
    public PageResponse<TradeResponseDTO> readBuyerTrades(Long buyerId, TradeStatus status, int page, int size) {
        List<Trade> trades = (status != null)
                ? tradeRepository.findAllByBuyer_MemberIdAndStatusOrderByTradeIdDesc(buyerId, status)
                : tradeRepository.findAllByBuyer_MemberIdOrderByTradeIdDesc(buyerId);

        List<TradeResponseDTO> tradeResponseDTOList = new ArrayList<>();
        for (Trade trade : trades) {
            tradeResponseDTOList.add(mapTradeResponse(trade));
        }

        return ServicePageResponse.of(tradeResponseDTOList, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    // 판매자 기준 거래 목록을 상태 필터와 함께 페이지 단위로 반환한다.
    // status != null 이면 DB 레벨에서 필터링하여 불필요한 전체 목록 로드를 방지한다.
    public PageResponse<TradeResponseDTO> readSellerTrades(Long sellerId, TradeStatus status, int page, int size) {
        List<Trade> trades = (status != null)
                ? tradeRepository.findAllBySeller_MemberIdAndStatusOrderByTradeIdDesc(sellerId, status)
                : tradeRepository.findAllBySeller_MemberIdOrderByTradeIdDesc(sellerId);

        List<TradeResponseDTO> tradeResponseDTOList = new ArrayList<>();
        for (Trade trade : trades) {
            tradeResponseDTOList.add(mapTradeResponse(trade));
        }

        return ServicePageResponse.of(tradeResponseDTOList, page, size);
    }

    @Override
    // 판매자만 구매 요청 상태의 거래를 수락할 수 있게 처리한다.
    public void acceptTrade(Long sellerId, Long tradeId) {
        Trade trade = getTrade(tradeId);

        if (!trade.getSeller().getMemberId().equals(sellerId)) {
            throw new IllegalArgumentException("거래 수락 권한이 없습니다.");
        }

        if (trade.getStatus() != TradeStatus.REQUESTED) {
            throw new IllegalArgumentException("구매 요청 상태의 거래만 수락할 수 있습니다.");
        }

        trade.accept();
        trade.getPost().changeStatus(PostStatus.RESERVED);

        // 거래 수락 시 채팅방에 안내 메시지
        chatService.sendSystemMessage(
                trade.getPost().getPostId(), trade.getBuyer().getMemberId(),
                trade.getDeliveryType() == TradeDeliveryType.DIRECT
                        ? "[RE:FORM] 판매자가 거래를 수락했습니다. 채팅으로 장소와 시간을 조율해 주세요."
                        : "[RE:FORM] 판매자가 거래를 수락했습니다. 결제를 진행해 주세요."
        );
        TradeNotificationTemplateDTO buyerAcceptedTemplate = notificationService.buildBuyerTradeAcceptedTemplate(
                trade.getTradeId(),
                trade.getPost().getTitle(),
                trade.getDeliveryType() == TradeDeliveryType.DIRECT
        );
        sendTradeNotice(trade.getBuyer(), buyerAcceptedTemplate);
        tradeRealtimeEventService.publishTradeUpdated(trade, "TRADE_ACCEPTED");
    }

    @Override
    // 판매자가 REQUESTED 또는 ACCEPTED 상태의 거래를 취소한다.
    // ACCEPTED 상태였다면 판매글을 ON_SALE로 되돌려 재거래를 가능하게 한다.
    public void cancelTrade(Long sellerId, Long tradeId) {
        Trade trade = getTrade(tradeId);

        // 판매자 본인 여부 확인
        if (!trade.getSeller().getMemberId().equals(sellerId)) {
            throw new IllegalArgumentException("거래 취소 권한이 없습니다.");
        }

        // 취소 가능한 상태는 REQUESTED(요청 거절) 또는 ACCEPTED(수락 철회)만 허용
        if (trade.getStatus() != TradeStatus.REQUESTED && trade.getStatus() != TradeStatus.ACCEPTED) {
            throw new IllegalArgumentException("구매 요청 또는 수락 상태의 거래만 취소할 수 있습니다.");
        }

        // ACCEPTED 상태였다면 게시글이 RESERVED로 변경된 상태이므로 ON_SALE로 복구
        boolean wasAccepted = trade.getStatus() == TradeStatus.ACCEPTED;
        trade.cancel();
        if (wasAccepted) {
            trade.getPost().changeStatus(PostStatus.ON_SALE);
        }

        // 거래 취소 시 채팅방에 안내 메시지
        chatService.sendSystemMessage(
                trade.getPost().getPostId(), trade.getBuyer().getMemberId(),
                "[RE:FORM] 판매자가 거래를 취소했습니다."
        );
        // 다른 상태 변경과 같이 구매자에게 알리고 실시간 화면을 갱신한다.
        sendTradeNotice(trade.getBuyer(), notificationService.buildBuyerTradeCanceledTemplate(
                trade.getTradeId(),
                trade.getPost().getTitle()
        ));
        tradeRealtimeEventService.publishTradeUpdated(trade, "TRADE_CANCELED");
    }

    @Override
    // 구매자만 결제 이후 거래를 구매 확정할 수 있게 처리한다.
    public void confirmTrade(Long buyerId, Long tradeId) {
        Trade trade = getTrade(tradeId);

        if (!trade.getBuyer().getMemberId().equals(buyerId)) {
            throw new IllegalArgumentException("구매 확정 권한이 없습니다.");
        }

        if (!isConfirmableStatus(trade)) {
            throw new IllegalArgumentException("구매 확정이 가능한 거래 상태가 아닙니다.");
        }

        trade.confirm();
        trade.getPost().changeStatus(PostStatus.SOLD);
        settleSellerPoint(trade);

        // 구매 확정 시 채팅방에 안내 메시지
        chatService.sendSystemMessage(
                trade.getPost().getPostId(), trade.getBuyer().getMemberId(),
                "[RE:FORM] 구매가 확정되었습니다. 거래가 완료되었습니다. 감사합니다!"
        );
        TradeNotificationTemplateDTO sellerConfirmedTemplate = notificationService.buildSellerTradeConfirmedTemplate(
                trade.getTradeId(),
                trade.getPost().getTitle()
        );
        TradeNotificationTemplateDTO buyerConfirmedTemplate = notificationService.buildBuyerTradeConfirmedTemplate(
                trade.getTradeId(),
                trade.getPost().getTitle()
        );
        sendTradeNotice(trade.getSeller(), sellerConfirmedTemplate);
        sendTradeNotice(trade.getBuyer(), buyerConfirmedTemplate);
        tradeRealtimeEventService.publishTradeUpdated(trade, "TRADE_CONFIRMED");
    }

    @Override
    public void completeTradeByAdmin(Long tradeId) {
        Trade trade = getTrade(tradeId);

        if (trade.getStatus() != TradeStatus.CONFIRMED) {
            throw new IllegalArgumentException("관리자 완료 처리는 CONFIRMED 상태의 거래만 가능합니다.");
        }

        trade.getPost().changeStatus(PostStatus.SOLD);
        settleSellerPoint(trade);
        trade.complete();
        tradeRealtimeEventService.publishTradeUpdated(trade, "TRADE_COMPLETED");
    }

    @Override
    // 거래 수령 방식과 요청자 권한에 맞는 주소 정보를 수정한다.
    public void modifyDelivery(Long requesterId, Long tradeId, DeliveryRequestDTO deliveryRequestDTO) {
        Trade trade = getTrade(tradeId);

        if (!isDeliveryEditableStatus(trade.getStatus())) {
            throw new IllegalArgumentException("현재 거래 상태에서는 배송지 정보를 수정할 수 없습니다.");
        }

        if (trade.getDeliveryType() == TradeDeliveryType.DELIVERY) {
            if (!trade.getBuyer().getMemberId().equals(requesterId)) {
                throw new IllegalArgumentException("배송지 수정 권한이 없습니다.");
            }
        } else if (trade.getDeliveryType() == TradeDeliveryType.DIRECT) {
            if (!trade.getSeller().getMemberId().equals(requesterId)) {
                throw new IllegalArgumentException("직거래 주소 수정 권한이 없습니다.");
            }
        }

        trade.changeDeliveryAddress(deliveryRequestDTO.deliveryAddress());
        if (trade.getDeliveryType() == TradeDeliveryType.DELIVERY) {
            sendTradeNotice(
                    trade.getSeller(),
                    notificationService.buildTradeDeliveryUpdatedTemplate(
                            trade.getTradeId(),
                            trade.getPost().getTitle(),
                            true,
                            true
                    )
            );
        } else {
            sendTradeNotice(
                    trade.getBuyer(),
                    notificationService.buildTradeDeliveryUpdatedTemplate(
                            trade.getTradeId(),
                            trade.getPost().getTitle(),
                            false,
                            false
                    )
            );
        }
        tradeRealtimeEventService.publishTradeUpdated(trade, "TRADE_DELIVERY_UPDATED");
    }

    @Override
    public void startShipping(Long sellerId, Long tradeId, TradeShippingRequestDTO requestDTO) {
        Trade trade = getTrade(tradeId);

        if (!trade.getSeller().getMemberId().equals(sellerId)) {
            throw new IllegalArgumentException("배송 정보 입력 권한이 없습니다.");
        }
        if (trade.getDeliveryType() != TradeDeliveryType.DELIVERY) {
            throw new IllegalArgumentException("택배 거래만 배송 정보를 입력할 수 있습니다.");
        }
        if (trade.getStatus() != TradeStatus.PAID) {
            throw new IllegalArgumentException("결제 완료된 거래만 배송 정보를 입력할 수 있습니다.");
        }
        if (trade.getDeliveryAddress() == null || trade.getDeliveryAddress().isBlank()) {
            throw new IllegalArgumentException("배송지 정보가 입력된 거래만 배송을 시작할 수 있습니다.");
        }

        // courierName은 프론트(TradePage)에서 이미 보유한 값을 그대로 전달받음
        // → 배송 시작 시마다 외부 택배사 목록 API를 호출하는 불필요한 I/O 제거
        trade.beginShippingProgress();
        trade.updateShippingInfo(requestDTO.courierCode(), requestDTO.courierName(), requestDTO.trackingNumber());

        TradeNotificationTemplateDTO buyerTemplate = notificationService.buildBuyerShippingRegisteredTemplate(
                trade.getTradeId(),
                trade.getPost().getTitle()
        );
        TradeNotificationTemplateDTO sellerTemplate = notificationService.buildSellerShippingRegisteredTemplate(
                trade.getTradeId(),
                trade.getPost().getTitle()
        );

        sendTradeNotice(trade.getBuyer(), buyerTemplate);
        sendTradeNotice(trade.getSeller(), sellerTemplate);

        // 배송 시작 시 채팅방에 안내 메시지
        chatService.sendSystemMessage(
                trade.getPost().getPostId(), trade.getBuyer().getMemberId(),
                "[RE:FORM] 판매자가 배송을 시작했습니다. 택배사: " + requestDTO.courierName()
                        + " / 송장번호: " + requestDTO.trackingNumber()
        );
        tradeRealtimeEventService.publishTradeUpdated(trade, "TRADE_SHIPPING_STARTED");
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryTrackingTraceResponseDTO readTradeTracking(Long requesterId, Long tradeId) {
        Trade trade = getTrade(tradeId);

        if (!isTradeParticipant(trade, requesterId)) {
            throw new IllegalArgumentException("거래 배송 조회 권한이 없습니다.");
        }
        if (!trade.hasShippingInfo()) {
            throw new IllegalArgumentException("배송 정보가 아직 입력되지 않았습니다.");
        }

        // DeliveryTrackingTraceRequestDTO(items, includeProgresses, skipCache)
        // includeProgresses=null → API 기본값 true (진행 이력 포함)
        // skipCache=null         → API 기본값 false (캐시 사용, 실시간 조회 시 과금 주의)
        DeliveryTrackingTraceRequestDTO requestDTO = new DeliveryTrackingTraceRequestDTO(
                List.of(new DeliveryTrackingTraceRequestDTO.TraceItemRequestDTO(
                        String.valueOf(trade.getTradeId()),
                        trade.getCourierCode(),
                        trade.getTrackingNumber()
                )),
                null, // includeProgresses
                null  // skipCache
        );
        return deliveryTrackingService.trace(requestDTO);
    }

    @Override
    // 거래 완료 후 구매자만 판매자에 대한 리뷰를 작성할 수 있게 처리한다.
    public Long addReview(Long buyerId, ReviewRequestDTO reviewRequestDTO) {
        Trade trade = getTrade(reviewRequestDTO.tradeId());

        if (!trade.getBuyer().getMemberId().equals(buyerId)) {
            throw new IllegalArgumentException("리뷰 작성 권한이 없습니다.");
        }

        if (mannerReviewRepository.existsByTrade_TradeIdAndBuyer_MemberId(reviewRequestDTO.tradeId(), buyerId)) {
            throw new IllegalArgumentException("이미 리뷰를 작성했습니다.");
        }

        if (trade.getStatus() != TradeStatus.COMPLETED && trade.getStatus() != TradeStatus.CONFIRMED) {
            throw new IllegalArgumentException("거래 완료 후에만 리뷰를 작성할 수 있습니다.");
        }

        validateReviewWritePeriod(trade);

        MannerReview review = MannerReview.builder()
                .trade(trade)
                .buyer(trade.getBuyer())
                .seller(trade.getSeller())
                .score(reviewRequestDTO.score())
                .content(reviewRequestDTO.content())
                .build();

        MannerReview savedReview = mannerReviewRepository.save(review);
        updateSellerMannerScore(trade.getSeller());

        return savedReview.getMannerId();
    }

    @Override
    @Transactional(readOnly = true)
    // 단건 리뷰를 화면용 응답 DTO로 반환한다.
    public ReviewResponseDTO readReview(Long mannerId) {
        MannerReview review = mannerReviewRepository.findById(mannerId)
                .orElseThrow(() -> new IllegalArgumentException("리뷰가 존재하지 않습니다."));
        return toReviewResponseDTO(review);
    }

    @Override
    @Transactional(readOnly = true)
    // 판매자 기준으로 받은 리뷰 목록을 페이지 단위로 반환한다.
    public PageResponse<ReviewResponseDTO> readSellerReviews(Long sellerId, int page, int size) {
        List<MannerReview> reviews = mannerReviewRepository.findAllBySeller_MemberIdOrderByMannerIdDesc(sellerId);
        List<ReviewResponseDTO> reviewResponseDTOList = new ArrayList<>();

        for (MannerReview review : reviews) {
            reviewResponseDTOList.add(toReviewResponseDTO(review));
        }

        return ServicePageResponse.of(reviewResponseDTOList, page, size);
    }

    // 거래 ID로 거래 엔티티를 조회한다.
    private Trade getTrade(Long tradeId) {
        return tradeRepository.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("거래가 존재하지 않습니다."));
    }

    // 거래 엔티티를 화면용 응답 DTO로 조립한다.
    private TradeResponseDTO mapTradeResponse(Trade trade) {
        boolean hasReview = mannerReviewRepository.existsByTrade_TradeIdAndBuyer_MemberId(
                trade.getTradeId(),
                trade.getBuyer().getMemberId()
        );

        PostBriefDTO postBriefDTO = toPostBriefDTO(trade.getPost());

        MemberBriefDTO buyer = new MemberBriefDTO(
                trade.getBuyer().getMemberId(),
                trade.getBuyer().getNickname(),
                trade.getBuyer().getProfileImageUrl()
        );
        MemberBriefDTO seller = new MemberBriefDTO(
                trade.getSeller().getMemberId(),
                trade.getSeller().getNickname(),
                trade.getSeller().getProfileImageUrl()
        );

        return new TradeResponseDTO(
                trade.getTradeId(),
                postBriefDTO,
                buyer,
                seller,
                trade.getStatus(),
                trade.getDeliveryType(),
                trade.getDeliveryAddress(),
                trade.getCourierCode(),
                trade.getCourierName(),
                trade.getTrackingNumber(),
                trade.getTradePrice(),
                trade.getShippingStartedAt(),
                trade.getCompletedAt(),
                trade.getConfirmedAt(),
                trade.getCreatedAt(),
                hasReview
        );
    }

    // 거래/채팅 화면에서 공통으로 쓰는 판매글 요약 DTO를 생성한다.
    private PostBriefDTO toPostBriefDTO(Post post) {
        List<PostImage> postImages = postImageRepository.findAllByPost_PostIdOrderBySortOrderAsc(post.getPostId());
        String thumbnailUrl = postImages.isEmpty() ? null : postImageService.getThumbnailUrl(postImages.get(0).getImageUrl());

        return new PostBriefDTO(
                post.getPostId(),
                post.getTitle(),
                thumbnailUrl,
                post.getPrice(),
                post.getStatus()
        );
    }

    // 리뷰 엔티티를 화면용 응답 DTO로 변환한다.
    private ReviewResponseDTO toReviewResponseDTO(MannerReview review) {
        MemberBriefDTO buyer = new MemberBriefDTO(
                review.getBuyer().getMemberId(),
                review.getBuyer().getNickname(),
                review.getBuyer().getProfileImageUrl()
        );
        MemberBriefDTO seller = new MemberBriefDTO(
                review.getSeller().getMemberId(),
                review.getSeller().getNickname(),
                review.getSeller().getProfileImageUrl()
        );

        return new ReviewResponseDTO(
                review.getMannerId(),
                review.getTrade().getTradeId(),
                buyer,
                seller,
                (int) Math.round(review.getScore()),
                review.getContent(),
                review.getCreatedAt()
        );
    }

    // 판매글 수령 방식을 거래 도메인 수령 방식 값으로 변환한다.
    private TradeDeliveryType resolveTradeDeliveryType(DeliveryType deliveryType, TradeDeliveryType selectedDeliveryType) {
        return switch (deliveryType) {
            case DIRECT -> {
                if (selectedDeliveryType != null && selectedDeliveryType != TradeDeliveryType.DIRECT) {
                    throw new IllegalArgumentException("직거래만 가능한 판매글입니다.");
                }
                yield TradeDeliveryType.DIRECT;
            }
            case DELIVERY -> {
                if (selectedDeliveryType != null && selectedDeliveryType != TradeDeliveryType.DELIVERY) {
                    throw new IllegalArgumentException("배송만 가능한 판매글입니다.");
                }
                yield TradeDeliveryType.DELIVERY;
            }
            case BOTH -> {
                if (selectedDeliveryType == null) {
                    throw new IllegalArgumentException("수령 방식을 선택해야 합니다.");
                }
                yield selectedDeliveryType;
            }
        };
    }

    // 거래가 구매 확정 가능한 상태인지 확인한다.
    private boolean isConfirmableStatus(Trade trade) {
        if (trade.getDeliveryType() == TradeDeliveryType.DIRECT) {
            return trade.getStatus() == TradeStatus.ACCEPTED;
        }
        return trade.getStatus() == TradeStatus.PAID
                || trade.getStatus() == TradeStatus.IN_PROGRESS
                || trade.getStatus() == TradeStatus.RECEIVED;
    }

    // 거래 주소 정보를 수정할 수 있는 상태인지 확인한다.
    private boolean isDeliveryEditableStatus(TradeStatus status) {
        return status == TradeStatus.REQUESTED
                || status == TradeStatus.ACCEPTED
                || status == TradeStatus.PAID;
    }

    private boolean isTradeParticipant(Trade trade, Long memberId) {
        return trade.getBuyer().getMemberId().equals(memberId)
                || trade.getSeller().getMemberId().equals(memberId);
    }

    private void validateReviewWritePeriod(Trade trade) {
        LocalDateTime baseTime = resolveReviewBaseTime(trade);
        if (LocalDateTime.now().isAfter(baseTime.plusDays(REVIEW_WRITE_PERIOD_DAYS))) {
            throw new IllegalArgumentException("리뷰는 거래 완료 후 5일 이내에만 작성할 수 있습니다.");
        }
    }

    private LocalDateTime resolveReviewBaseTime(Trade trade) {
        if (trade.getCompletedAt() != null) {
            return trade.getCompletedAt();
        }
        if (trade.getConfirmedAt() != null) {
            return trade.getConfirmedAt();
        }
        throw new IllegalArgumentException("거래 완료 시각이 없어 리뷰를 작성할 수 없습니다.");
    }

    private void updateSellerMannerScore(Member seller) {
        Double averageScore = mannerReviewRepository.findAverageScoreBySellerId(seller.getMemberId());
        BigDecimal mannerScore = averageScore == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(averageScore).setScale(2, RoundingMode.HALF_UP);
        seller.updateMannerScore(mannerScore);
    }

    private void sendTradeNotice(Member member, TradeNotificationTemplateDTO template) {
        notificationService.createTradeNotification(member, template.content(), template.linkUrl());
        mailService.sendTradeNotificationEmail(member, template);
    }

    private void settleSellerPoint(Trade trade) {
        if (pointHistoryRepository.existsByTrade_TradeId(trade.getTradeId())) {
            return;
        }

        PointWallet sellerWallet = pointWalletRepository.findByMemberMemberId(
                        trade.getSeller().getMemberId())
                .orElseThrow(() -> new IllegalStateException(
                        "settleSellerPoint : 판매자 포인트 지갑이 존재하지 않습니다. (sellerId=" + trade.getSeller().getMemberId() + ")"));

        int tradePrice = trade.getTradePrice();
        sellerWallet.confirm(tradePrice, tradePrice);
        pointWalletRepository.save(sellerWallet);

        pointHistoryRepository.save(PointHistory.builder()
                .pointWallet(sellerWallet)
                .type(PointHistoryType.EARN)
                .changeAmount(tradePrice)
                .balance(sellerWallet.getBalance())
                .trade(trade)
                .build());
    }


}
