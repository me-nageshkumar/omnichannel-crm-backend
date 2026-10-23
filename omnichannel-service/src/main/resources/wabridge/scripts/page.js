(() => {
  var __create = Object.create;
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __getProtoOf = Object.getPrototypeOf;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __commonJS = (cb, mod) => function __require() {
    return mod || (0, cb[__getOwnPropNames(cb)[0]])((mod = { exports: {} }).exports, mod), mod.exports;
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
    // If the importer is in node compatibility mode or this is not an ESM
    // file that has been converted to a CommonJS file using a Babel-
    // compatible transform (i.e. "__esModule" has not been set), then set
    // "default" to the CommonJS "module.exports" for node compatibility.
    isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
    mod
  ));

  // ../whatsapp-web.js/src/util/Injected/Utils.js
  var require_Utils = __commonJS({
    "../whatsapp-web.js/src/util/Injected/Utils.js"(exports) {
      "use strict";
      exports.LoadUtils = () => {
        window.WWebJS = {};
        window.WWebJS.compareWwebVersions = (lOperand, operator, rOperand) => {
          if (![">", ">=", "<", "<=", "="].includes(operator)) {
            throw new class _ extends Error {
              constructor(m) {
                super(m);
                this.name = "CompareWwebVersionsError";
              }
            }("Invalid comparison operator is provided");
          }
          if (typeof lOperand !== "string" || typeof rOperand !== "string") {
            throw new class _ extends Error {
              constructor(m) {
                super(m);
                this.name = "CompareWwebVersionsError";
              }
            }("A non-string WWeb version type is provided");
          }
          lOperand = lOperand.replace(/-beta$/, "");
          rOperand = rOperand.replace(/-beta$/, "");
          while (lOperand.length !== rOperand.length) {
            lOperand.length > rOperand.length ? rOperand = rOperand.concat("0") : lOperand = lOperand.concat("0");
          }
          lOperand = Number(lOperand.replace(/\./g, ""));
          rOperand = Number(rOperand.replace(/\./g, ""));
          return operator === ">" ? lOperand > rOperand : operator === ">=" ? lOperand >= rOperand : operator === "<" ? lOperand < rOperand : operator === "<=" ? lOperand <= rOperand : operator === "=" ? lOperand === rOperand : false;
        };
        window.WWebJS.injectToFunction = (target, callback) => {
          try {
            let module2 = window.require(target.module);
            if (!module2) return;
            const path = target.function.split(".");
            const funcName = path.pop();
            for (const key of path) {
              if (!module2[key]) return;
              module2 = module2[key];
            }
            const originalFunction = module2[funcName];
            if (typeof originalFunction !== "function") return;
            module2[funcName] = ((...args) => {
              try {
                return callback(module2, originalFunction, ...args);
              } catch {
                return originalFunction.apply(module2, args);
              }
            }).bind(module2);
          } catch {
            return;
          }
        };
        window.WWebJS.injectToFunction(
          { module: "WAWebBackendJobsCommon", function: "mediaTypeFromProtobuf" },
          (module2, func, ...args) => {
            const [proto] = args;
            return proto.locationMessage ? null : func(...args);
          }
        );
        window.WWebJS.injectToFunction(
          { module: "WAWebE2EProtoUtils", function: "typeAttributeFromProtobuf" },
          (module2, func, ...args) => {
            const [proto] = args;
            return proto.locationMessage || proto.groupInviteMessage ? "text" : func(...args);
          }
        );
        window.WWebJS.forwardMessage = async (chatId, msgId) => {
          const msg = window.require("WAWebCollections").Msg.get(msgId) || (await window.require("WAWebCollections").Msg.getMessagesById([msgId]))?.messages?.[0];
          const chat = await window.WWebJS.getChat(chatId, { getAsModel: false });
          return await window.require("WAWebChatForwardMessage").forwardMessages({
            chat,
            msgs: [msg],
            multicast: true,
            includeCaption: true,
            appendedText: void 0
          });
        };
        window.WWebJS.sendSeen = async (chatId) => {
          const chat = await window.WWebJS.getChat(chatId, { getAsModel: false });
          if (chat) {
            window.require("WAWebStreamModel").Stream.markAvailable();
            await window.require("WAWebUpdateUnreadChatAction").sendSeen({
              chat,
              threadId: void 0
            });
            window.require("WAWebStreamModel").Stream.markUnavailable();
            return true;
          }
          return false;
        };
        window.WWebJS.sendMessage = async (chat, content, options = {}) => {
          const { getIsNewsletter, getIsBroadcast } = window.require("WAWebChatGetters");
          const isChannel = getIsNewsletter(chat);
          const isStatus = getIsBroadcast(chat);
          const { findLink } = window.require("WALinkify");
          let mediaOptions = {};
          if (options.media) {
            mediaOptions = options.sendMediaAsSticker && !isChannel && !isStatus ? await window.WWebJS.processStickerData(options.media) : await window.WWebJS.processMediaData(options.media, {
              forceSticker: options.sendMediaAsSticker,
              forceGif: options.sendVideoAsGif,
              forceVoice: options.sendAudioAsVoice,
              forceDocument: options.sendMediaAsDocument,
              forceMediaHd: options.sendMediaAsHd,
              sendToChannel: isChannel,
              sendToStatus: isStatus
            });
            mediaOptions.caption = options.caption;
            content = options.sendMediaAsSticker ? void 0 : mediaOptions.preview;
            mediaOptions.isViewOnce = options.isViewOnce;
            delete options.media;
            delete options.sendMediaAsSticker;
          }
          let quotedMsgOptions = {};
          if (options.quotedMessageId) {
            let quotedMessage = window.require("WAWebCollections").Msg.get(options.quotedMessageId);
            !quotedMessage && (quotedMessage = (await window.require("WAWebCollections").Msg.getMessagesById([options.quotedMessageId]))?.messages?.[0]);
            if (quotedMessage) {
              const ReplyUtils = window.require("WAWebMsgReply");
              const canReply = ReplyUtils ? ReplyUtils.canReplyMsg(quotedMessage.unsafe()) : quotedMessage.canReply();
              if (canReply) {
                quotedMsgOptions = quotedMessage.msgContextInfo(chat);
              }
            } else {
              if (!options.ignoreQuoteErrors) {
                throw new Error("Could not get the quoted message.");
              }
            }
            delete options.ignoreQuoteErrors;
            delete options.quotedMessageId;
          }
          if (options.mentionedJidList) {
            options.mentionedJidList = options.mentionedJidList.map(
              (id) => window.require("WAWebWidFactory").createWid(id)
            );
            options.mentionedJidList = options.mentionedJidList.filter(Boolean);
          }
          if (options.groupMentions) {
            options.groupMentions = options.groupMentions.map((e) => ({
              groupSubject: e.subject,
              groupJid: window.require("WAWebWidFactory").createWid(e.id)
            }));
          }
          let locationOptions = {};
          if (options.location) {
            let { latitude, longitude, description, url } = options.location;
            url = findLink(url)?.href;
            url && !description && (description = url);
            locationOptions = {
              type: "location",
              loc: description,
              lat: latitude,
              lng: longitude,
              clientUrl: url
            };
            delete options.location;
          }
          let pollOptions = {};
          if (options.poll) {
            const { pollName, pollOptions: _pollOptions } = options.poll;
            const { allowMultipleAnswers, messageSecret } = options.poll.options;
            pollOptions = {
              kind: "pollCreation",
              type: "poll_creation",
              pollName,
              pollOptions: _pollOptions,
              pollSelectableOptionsCount: allowMultipleAnswers ? 0 : 1,
              messageSecret: Array.isArray(messageSecret) && messageSecret.length === 32 ? new Uint8Array(messageSecret) : window.crypto.getRandomValues(new Uint8Array(32))
            };
            delete options.poll;
          }
          let eventOptions = {};
          if (options.event) {
            const { name, startTimeTs, eventSendOptions } = options.event;
            const { messageSecret } = eventSendOptions;
            eventOptions = {
              type: "event_creation",
              eventName: name,
              eventDescription: eventSendOptions.description,
              eventStartTime: startTimeTs,
              eventEndTime: eventSendOptions.endTimeTs,
              eventLocation: eventSendOptions.location && {
                degreesLatitude: 0,
                degreesLongitude: 0,
                name: eventSendOptions.location
              },
              eventJoinLink: eventSendOptions.callType === "none" ? null : await window.require("WAWebGenerateEventCallLink").createEventCallLink(
                startTimeTs,
                eventSendOptions.callType
              ),
              isEventCanceled: eventSendOptions.isEventCanceled,
              messageSecret: Array.isArray(messageSecret) && messageSecret.length === 32 ? new Uint8Array(messageSecret) : window.crypto.getRandomValues(new Uint8Array(32))
            };
            delete options.event;
          }
          let vcardOptions = {};
          if (options.contactCard) {
            let contact = await window.require("WAWebCollections").Contact.find(options.contactCard);
            vcardOptions = {
              body: window.require("WAWebFrontendVcardUtils").vcardFromContactModel(contact).vcard,
              type: "vcard",
              vcardFormattedName: contact.formattedName
            };
            delete options.contactCard;
          } else if (options.contactCardList) {
            let contacts = await Promise.all(
              options.contactCardList.map(
                (c) => window.require("WAWebCollections").Contact.find(c)
              )
            );
            let vcards = contacts.map(
              (c) => window.require("WAWebFrontendVcardUtils").vcardFromContactModel(c)
            );
            vcardOptions = {
              type: "multi_vcard",
              vcardList: vcards,
              body: null
            };
            delete options.contactCardList;
          } else if (options.parseVCards && typeof content === "string" && content.startsWith("BEGIN:VCARD")) {
            delete options.parseVCards;
            delete options.linkPreview;
            try {
              const parsed = window.require("WAWebVcardParsingUtils").parseVcard(content);
              if (parsed) {
                vcardOptions = {
                  type: "vcard",
                  vcardFormattedName: window.require("WAWebVcardGetNameFromParsed").vcardGetNameFromParsed(parsed)
                };
              }
            } catch (ignoredError) {
            }
          }
          if (options.linkPreview) {
            delete options.linkPreview;
            const link = findLink(content);
            if (link) {
              let preview = await window.require("WAWebLinkPreviewChatAction").getLinkPreview(link);
              if (preview && preview.data) {
                preview = preview.data;
                preview.preview = true;
                preview.subtype = "url";
                options = { ...options, ...preview };
              }
            }
          }
          let buttonOptions = {};
          if (options.buttons) {
            let caption;
            if (options.buttons.type === "chat") {
              content = options.buttons.body;
              caption = content;
            } else {
              caption = options.caption ? options.caption : " ";
            }
            buttonOptions = {
              productHeaderImageRejected: false,
              isFromTemplate: false,
              isDynamicReplyButtonsMsg: true,
              title: options.buttons.title ? options.buttons.title : void 0,
              footer: options.buttons.footer ? options.buttons.footer : void 0,
              dynamicReplyButtons: options.buttons.buttons,
              replyButtons: options.buttons.buttons,
              caption
            };
            delete options.buttons;
          }
          let listOptions = {};
          if (options.list) {
            if (window.require("WAWebConnModel").Conn.platform === "smba" || window.require("WAWebConnModel").Conn.platform === "smbi") {
              throw "[LT01] Whatsapp business can't send this yet";
            }
            listOptions = {
              type: "list",
              footer: options.list.footer,
              list: {
                ...options.list,
                listType: 1
              },
              body: options.list.description
            };
            delete options.list;
            delete listOptions.list.footer;
          }
          const botOptions = {};
          if (options.invokedBotWid) {
            botOptions.messageSecret = window.crypto.getRandomValues(
              new Uint8Array(32)
            );
            botOptions.botMessageSecret = await window.require("WAWebBotMessageSecret").genBotMsgSecretFromMsgSecret(botOptions.messageSecret);
            botOptions.invokedBotWid = window.require("WAWebWidFactory").createWid(options.invokedBotWid);
            botOptions.botPersonaId = window.require("WAWebBotProfileCollection").BotProfileCollection.get(options.invokedBotWid).personaId;
            delete options.invokedBotWid;
          }
          const { getMaybeMeLidUser, getMaybeMePnUser } = window.require(
            "WAWebUserPrefsMeUser"
          );
          const lidUser = getMaybeMeLidUser();
          const meUser = getMaybeMePnUser();
          const newId = await window.require("WAWebMsgKey").newId();
          let from = chat.id.isLid() ? lidUser : meUser;
          let participant;
          if (typeof chat.id?.isGroup === "function" && chat.id.isGroup()) {
            from = chat.groupMetadata && chat.groupMetadata.isLidAddressingMode ? lidUser : meUser;
            participant = window.require("WAWebWidFactory").asUserWidOrThrow(from);
          }
          if (typeof chat.id?.isStatus === "function" && chat.id.isStatus()) {
            participant = window.require("WAWebWidFactory").asUserWidOrThrow(from);
          }
          const newMsgKey = new (window.require("WAWebMsgKey"))({
            from,
            to: chat.id,
            id: newId,
            participant,
            selfDir: "out"
          });
          const extraOptions = options.extraOptions || {};
          delete options.extraOptions;
          const ephemeralFields = window.require("WAWebGetEphemeralFieldsMsgActionsUtils").getEphemeralFields(chat);
          const message = {
            ...options,
            id: newMsgKey,
            ack: 0,
            body: content,
            from,
            to: chat.id,
            local: true,
            self: "out",
            t: parseInt((/* @__PURE__ */ new Date()).getTime() / 1e3),
            isNewMsg: true,
            type: "chat",
            ...ephemeralFields,
            ...mediaOptions,
            ...mediaOptions.toJSON ? mediaOptions.toJSON() : {},
            ...quotedMsgOptions,
            ...locationOptions,
            ...pollOptions,
            ...eventOptions,
            ...vcardOptions,
            ...buttonOptions,
            ...listOptions,
            ...botOptions,
            ...extraOptions
          };
          if (botOptions) {
            delete message.canonicalUrl;
          }
          if (isChannel) {
            const msg = new (window.require("WAWebCollections")).Msg.modelClass(
              message
            );
            const msgDataFromMsgModel = window.require("WAWebMsgDataFromModel").msgDataFromMsgModel(msg);
            const isMedia = Object.keys(mediaOptions).length > 0;
            await window.require("WAWebNewsletterUpdateMsgsRecordsJob").addNewsletterMsgsRecords([msgDataFromMsgModel]);
            chat.msgs.add(msg);
            chat.t = msg.t;
            const sendChannelMsgResponse = await window.require("WAWebNewsletterSendMessageJob").sendNewsletterMessageJob({
              msg,
              type: message.type === "chat" ? "text" : isMedia ? "media" : "pollCreation",
              newsletterJid: chat.id.toJid(),
              ...isMedia ? {
                mediaMetadata: msg.avParams(),
                mediaHandle: isMedia ? mediaOptions.mediaHandle : null
              } : {}
            });
            if (sendChannelMsgResponse.success) {
              msg.t = sendChannelMsgResponse.ack.t;
              msg.serverId = sendChannelMsgResponse.serverId;
            }
            msg.updateAck(1, true);
            await window.require("WAWebNewsletterUpdateMsgsRecordsJob").updateNewsletterMsgRecord(msg);
            return msg;
          }
          if (isStatus) {
            const { backgroundColor, fontStyle } = extraOptions;
            const isMedia = Object.keys(mediaOptions).length > 0;
            const mediaUpdate = (data) => window.require("WAWebMediaUpdateMsg")(data, mediaOptions);
            const msg = new (window.require("WAWebCollections")).Msg.modelClass(
              {
                ...message,
                author: participant ? participant : null,
                messageSecret: window.crypto.getRandomValues(
                  new Uint8Array(32)
                ),
                cannotBeRanked: window.require("WAWebStatusGatingUtils").canCheckStatusRankingPosterGating()
              }
            );
            const statusOptions = {
              color: backgroundColor && window.WWebJS.assertColor(backgroundColor) || 4286237861,
              font: fontStyle >= 0 && fontStyle <= 7 && fontStyle || 0,
              text: msg.body
            };
            await window.require("WAWebSendStatusMsgAction")[isMedia ? "sendStatusMediaMsgAction" : "sendStatusTextMsgAction"](...isMedia ? [msg, mediaUpdate] : [statusOptions]);
            return msg;
          }
          const [msgPromise, sendMsgResultPromise] = window.require("WAWebSendMsgChatAction").addAndSendMsgToChat(chat, message);
          await msgPromise;
          if (options.waitUntilMsgSent) await sendMsgResultPromise;
          return window.require("WAWebCollections").Msg.get(newMsgKey._serialized);
        };
        window.WWebJS.editMessage = async (msg, content, options = {}) => {
          const extraOptions = options.extraOptions || {};
          delete options.extraOptions;
          if (options.mentionedJidList) {
            options.mentionedJidList = options.mentionedJidList.map(
              (id) => window.require("WAWebWidFactory").createWid(id)
            );
            options.mentionedJidList = options.mentionedJidList.filter(Boolean);
          }
          if (options.groupMentions) {
            options.groupMentions = options.groupMentions.map((e) => ({
              groupSubject: e.subject,
              groupJid: window.require("WAWebWidFactory").createWid(e.id)
            }));
          }
          if (options.linkPreview) {
            const { findLink } = window.require("WALinkify");
            delete options.linkPreview;
            const link = findLink(content);
            if (link) {
              const preview = await window.require("WAWebLinkPreviewChatAction").getLinkPreview(link);
              preview.preview = true;
              preview.subtype = "url";
              options = { ...options, ...preview };
            }
          }
          const internalOptions = {
            ...options,
            ...extraOptions
          };
          await window.require("WAWebSendMessageEditAction").sendMessageEdit(msg, content, internalOptions);
          return window.require("WAWebCollections").Msg.get(msg.id._serialized);
        };
        window.WWebJS.toStickerData = async (mediaInfo) => {
          if (mediaInfo.mimetype == "image/webp") return mediaInfo;
          const file = window.WWebJS.mediaInfoToFile(mediaInfo);
          const webpSticker = await window.require("WAWebImageUtils").toWebpSticker(file);
          const webpBuffer = await webpSticker.arrayBuffer();
          const data = window.WWebJS.arrayBufferToBase64(webpBuffer);
          return {
            mimetype: "image/webp",
            data
          };
        };
        window.WWebJS.processStickerData = async (mediaInfo) => {
          if (mediaInfo.mimetype !== "image/webp")
            throw new Error("Invalid media type");
          const file = window.WWebJS.mediaInfoToFile(mediaInfo);
          let filehash = await window.WWebJS.getFileHash(file);
          let mediaKey = await window.WWebJS.generateHash(32);
          const controller = new AbortController();
          const uploadedInfo = await window.require("WAWebUploadManager").encryptAndUpload({
            blob: file,
            type: "sticker",
            signal: controller.signal,
            mediaKey,
            uploadQpl: window.require("WAWebStartMediaUploadQpl").startMediaUploadQpl({
              entryPoint: "MediaUpload"
            })
          });
          const stickerInfo = {
            ...uploadedInfo,
            clientUrl: uploadedInfo.url,
            deprecatedMms3Url: uploadedInfo.url,
            uploadhash: uploadedInfo.encFilehash,
            size: file.size,
            type: "sticker",
            filehash
          };
          return stickerInfo;
        };
        window.WWebJS.processMediaData = async (mediaInfo, {
          forceSticker,
          forceGif,
          forceVoice,
          forceDocument,
          forceMediaHd,
          sendToChannel,
          sendToStatus
        }) => {
          const file = window.WWebJS.mediaInfoToFile(mediaInfo);
          const OpaqueData = window.require("WAWebMediaOpaqueData");
          const opaqueData = await OpaqueData.createFromData(
            file,
            mediaInfo.mimetype
          );
          const mediaParams = {
            asSticker: forceSticker,
            asGif: forceGif,
            isPtt: forceVoice,
            asDocument: forceDocument
          };
          if (forceMediaHd && file.type.indexOf("image/") === 0) {
            mediaParams.maxDimension = 2560;
          }
          const mediaPrep = window.require("WAWebPrepRawMedia").prepRawMedia(opaqueData, mediaParams);
          const mediaData = await mediaPrep.waitForPrep();
          const mediaObject = window.require("WAWebMediaStorage").getOrCreateMediaObject(mediaData.filehash);
          const mediaType = window.require("WAWebMmsMediaTypes").msgToMediaType({
            type: mediaData.type,
            isGif: mediaData.isGif,
            isNewsletter: sendToChannel
          });
          if (!mediaData.filehash) {
            throw new Error("media-fault: sendToChat filehash undefined");
          }
          if (forceVoice && mediaData.type === "ptt" || sendToStatus && mediaData.type === "audio") {
            const waveform = mediaObject.contentInfo.waveform;
            mediaData.waveform = waveform || await window.WWebJS.generateWaveform(file);
          }
          if (!(mediaData.mediaBlob instanceof OpaqueData)) {
            mediaData.mediaBlob = await OpaqueData.createFromData(
              mediaData.mediaBlob,
              mediaData.mediaBlob.type
            );
          }
          mediaData.renderableUrl = mediaData.mediaBlob.url();
          mediaObject.consolidate(mediaData.toJSON());
          mediaData.mediaBlob.autorelease();
          const shouldUseMediaCache = window.require("WAWebMediaDataUtils").shouldUseMediaCache(
            window.require("WAWebMmsMediaTypes").castToV4(mediaObject.type)
          );
          if (shouldUseMediaCache && mediaData.mediaBlob instanceof OpaqueData) {
            const formData = mediaData.mediaBlob.formData();
            window.require("WAWebMediaInMemoryBlobCache").InMemoryMediaBlobCache.put(mediaObject.filehash, formData);
          }
          const dataToUpload = {
            mimetype: mediaData.mimetype,
            mediaObject,
            mediaType,
            ...sendToChannel ? {
              calculateToken: window.require("WAMediaCalculateFilehash").getRandomFilehash
            } : {}
          };
          const { uploadMedia, uploadUnencryptedMedia } = window.require(
            "WAWebMediaMmsV4Upload"
          );
          const uploadedMedia = !sendToChannel ? await uploadMedia(dataToUpload) : await uploadUnencryptedMedia(dataToUpload);
          const mediaEntry = uploadedMedia.mediaEntry;
          if (!mediaEntry) {
            throw new Error("upload failed: media entry was not created");
          }
          mediaData.set({
            clientUrl: mediaEntry.mmsUrl,
            deprecatedMms3Url: mediaEntry.deprecatedMms3Url,
            directPath: mediaEntry.directPath,
            mediaKey: mediaEntry.mediaKey,
            mediaKeyTimestamp: mediaEntry.mediaKeyTimestamp,
            filehash: mediaObject.filehash,
            encFilehash: mediaEntry.encFilehash,
            uploadhash: mediaEntry.uploadHash,
            size: mediaObject.size,
            streamingSidecar: mediaEntry.sidecar,
            firstFrameSidecar: mediaEntry.firstFrameSidecar,
            mediaHandle: sendToChannel ? mediaEntry.handle : null
          });
          return mediaData;
        };
        window.WWebJS.getMessageModel = (message) => {
          const msg = message.serialize();
          const { findLinks } = window.require("WALinkify");
          msg.isEphemeral = message.isEphemeral;
          msg.isStatusV3 = message.isStatusV3;
          msg.links = findLinks(
            message.mediaObject ? message.caption : message.body
          ).map((link) => ({
            link: link.href,
            isSuspicious: Boolean(
              link.suspiciousCharacters && link.suspiciousCharacters.size
            )
          }));
          if (msg.buttons) {
            msg.buttons = msg.buttons.serialize();
          }
          if (msg.dynamicReplyButtons) {
            msg.dynamicReplyButtons = JSON.parse(
              JSON.stringify(msg.dynamicReplyButtons)
            );
          }
          if (msg.replyButtons) {
            msg.replyButtons = JSON.parse(JSON.stringify(msg.replyButtons));
          }
          if (typeof msg.id.remote === "object") {
            msg.id = Object.assign({}, msg.id, {
              remote: msg.id.remote._serialized
            });
          }
          delete msg.pendingAckUpdate;
          return msg;
        };
        window.WWebJS.getChat = async (chatId, { getAsModel = true } = {}) => {
          const isChannel = /@\w*newsletter\b/.test(chatId);
          const chatWid = window.require("WAWebWidFactory").createWid(chatId);
          let chat;
          if (isChannel) {
            try {
              chat = window.require("WAWebCollections").WAWebNewsletterCollection.get(chatId);
              if (!chat) {
                await window.require("WAWebLoadNewsletterPreviewChatAction").loadNewsletterPreviewChat(chatId);
                chat = await window.require("WAWebCollections").WAWebNewsletterCollection.find(chatWid);
              }
            } catch (ignoredError) {
              chat = null;
            }
          } else {
            chat = window.require("WAWebCollections").Chat.get(chatWid) || (await window.require("WAWebFindChatAction").findOrCreateLatestChat(chatWid))?.chat;
          }
          return getAsModel && chat ? await window.WWebJS.getChatModel(chat, { isChannel }) : chat;
        };
        window.WWebJS.getChannelMetadata = async (inviteCode) => {
          const role = window.require("WAWebNewsletterModelUtils").getRoleByIdentifier(inviteCode);
          const response2 = await window.require("WAWebNewsletterMetadataQueryJob").queryNewsletterMetadataByInviteCode(inviteCode, role);
          const picUrl = response2.newsletterPictureMetadataMixin?.picture[0]?.queryPictureDirectPathOrEmptyResponseMixinGroup.value.directPath;
          return {
            id: response2.idJid,
            createdAtTs: response2.newsletterCreationTimeMetadataMixin.creationTimeValue,
            titleMetadata: {
              title: response2.newsletterNameMetadataMixin.nameElementValue,
              updatedAtTs: response2.newsletterNameMetadataMixin.nameUpdateTime
            },
            descriptionMetadata: {
              description: response2.newsletterDescriptionMetadataMixin.descriptionQueryDescriptionResponseMixin.elementValue,
              updatedAtTs: response2.newsletterDescriptionMetadataMixin.descriptionQueryDescriptionResponseMixin.updateTime
            },
            inviteLink: `https://whatsapp.com/channel/${response2.newsletterInviteLinkMetadataMixin.inviteCode}`,
            membershipType: role,
            stateType: response2.newsletterStateMetadataMixin.stateType,
            pictureUrl: picUrl ? `https://pps.whatsapp.net${picUrl}` : null,
            subscribersCount: response2.newsletterSubscribersMetadataMixin.subscribersCount,
            isVerified: response2.newsletterVerificationMetadataMixin.verificationState === "verified"
          };
        };
        window.WWebJS.getChats = async () => {
          const chats = window.require("WAWebCollections").Chat.getModelsArray();
          const chatPromises = chats.map(
            (chat) => window.WWebJS.getChatModel(chat)
          );
          return await Promise.all(chatPromises);
        };
        window.WWebJS.getChannels = async () => {
          const channels = window.require("WAWebCollections").WAWebNewsletterCollection.getModelsArray();
          const channelPromises = channels?.map(
            (channel) => window.WWebJS.getChatModel(channel, { isChannel: true })
          );
          return await Promise.all(channelPromises);
        };
        window.WWebJS.getChatModel = async (chat, { isChannel = false } = {}) => {
          if (!chat) return null;
          const model = chat.serialize();
          model.isGroup = false;
          model.isMuted = chat.mute?.expiration !== 0;
          if (isChannel) {
            model.isChannel = window.require("WAWebChatGetters").getIsNewsletter(chat);
          } else {
            model.formattedTitle = chat.formattedTitle;
          }
          if (chat.groupMetadata) {
            model.isGroup = true;
            const chatWid = window.require("WAWebWidFactory").createWid(chat.id._serialized);
            const groupMetadata = window.require("WAWebCollections").GroupMetadata || window.require("WAWebCollections").WAWebGroupMetadataCollection;
            await groupMetadata.update(chatWid);
            const { toPn } = window.require("WAWebLidMigrationUtils");
            const serializedMetadata = chat.groupMetadata.serialize();
            for (const p of serializedMetadata.participants || []) {
              p.id = toPn(p.id) ?? p.id;
            }
            model.groupMetadata = serializedMetadata;
            model.isReadOnly = chat.groupMetadata.announce;
          }
          if (chat.newsletterMetadata) {
            const newsletterMetadata = window.require("WAWebCollections").NewsletterMetadataCollection || window.require("WAWebCollections").WAWebNewsletterMetadataCollection;
            await newsletterMetadata.update(chat.id);
            model.channelMetadata = chat.newsletterMetadata.serialize();
            model.channelMetadata.createdAtTs = chat.newsletterMetadata.creationTime;
          }
          model.lastMessage = null;
          if (model.msgs && model.msgs.length) {
            const lastMessage = chat.lastReceivedKey ? window.require("WAWebCollections").Msg.get(chat.lastReceivedKey._serialized) || (await window.require("WAWebCollections").Msg.getMessagesById([
              chat.lastReceivedKey._serialized
            ]))?.messages?.[0] : null;
            lastMessage && (model.lastMessage = window.WWebJS.getMessageModel(lastMessage));
          }
          delete model.msgs;
          delete model.msgUnsyncedButtonReplyMsgs;
          delete model.unsyncedButtonReplies;
          return model;
        };
        window.WWebJS.getContactModel = (contact) => {
          let res = contact.serialize();
          const wid = window.require("WAWebWidFactory").createWidFromWidLike(contact.id);
          if (wid.isLid() && contact.phoneNumber) {
            res.id = contact.phoneNumber;
          }
          res.isBusiness = contact.isBusiness === void 0 ? false : contact.isBusiness;
          if (contact.businessProfile) {
            res.businessProfile = contact.businessProfile.serialize();
          }
          res.isBlocked = contact.isContactBlocked;
          if (!res.isBlocked) {
            const alt = window.require("WAWebApiContact").getAlternateUserWid(wid);
            if (alt) {
              res.isBlocked = !!window.require("WAWebCollections").Blocklist.get(alt);
            }
          }
          const ContactMethods = window.require("WAWebContactGetters");
          res.isMe = ContactMethods.getIsMe(contact);
          res.isUser = ContactMethods.getIsUser(contact);
          res.isGroup = ContactMethods.getIsGroup(contact);
          res.isWAContact = ContactMethods.getIsWAContact(contact);
          res.userid = ContactMethods.getUserid(contact);
          res.verifiedName = ContactMethods.getVerifiedName(contact);
          res.verifiedLevel = ContactMethods.getVerifiedLevel(contact);
          res.statusMute = ContactMethods.getStatusMute(contact);
          res.name = ContactMethods.getName(contact);
          res.shortName = ContactMethods.getShortName(contact);
          res.pushname = ContactMethods.getPushname(contact);
          const { getIsMyContact } = window.require(
            "WAWebFrontendContactGetters"
          );
          res.isMyContact = getIsMyContact(contact);
          res.isEnterprise = ContactMethods.getIsEnterprise(contact);
          return res;
        };
        window.WWebJS.getContact = async (contactId) => {
          const contactWid = window.require("WAWebWidFactory").createWid(contactId);
          const contact = await window.require("WAWebCollections").Contact.find(contactWid);
          if (contact.isBusiness || contact.isEnterprise) {
            const bizProfile = await window.require("WAWebCollections").BusinessProfile.find(contactWid);
            bizProfile.profileOptions && (contact.businessProfile = bizProfile);
          }
          return window.WWebJS.getContactModel(contact);
        };
        window.WWebJS.getContacts = () => {
          const contacts = window.require("WAWebCollections").Contact.getModelsArray();
          return Promise.all(
            contacts.map(async (contact) => {
              if (contact.isBusiness || contact.isEnterprise) {
                await window.require("WAWebCollections").BusinessProfile.find(contact.id).catch(() => {
                });
              }
              return window.WWebJS.getContactModel(contact);
            })
          );
        };
        window.WWebJS.mediaInfoToFile = ({ data, mimetype, filename }) => {
          const binaryData = window.atob(data);
          const buffer = new ArrayBuffer(binaryData.length);
          const view = new Uint8Array(buffer);
          for (let i = 0; i < binaryData.length; i++) {
            view[i] = binaryData.charCodeAt(i);
          }
          const blob = new Blob([buffer], { type: mimetype });
          return new File([blob], filename, {
            type: mimetype,
            lastModified: Date.now()
          });
        };
        window.WWebJS.resolveMediaBlob = async (msgId) => {
          const { Msg } = window.require("WAWebCollections");
          const msg = Msg.get(msgId) || (await Msg.getMessagesById([msgId]))?.messages?.[0];
          if (!msg || !msg.mediaData || msg.mediaData.mediaStage === "REUPLOADING") {
            return null;
          }
          await msg.downloadMedia({
            downloadEvenIfExpensive: true,
            rmrReason: 1,
            isUserInitiated: true
          });
          if (msg.mediaData.mediaStage.includes("ERROR") || msg.mediaData.mediaStage === "FETCHING") {
            return null;
          }
          const cached = window.require("WAWebMediaInMemoryBlobCache").InMemoryMediaBlobCache.get(msg.mediaObject?.filehash);
          let blob;
          if (cached) {
            blob = cached;
          } else if (msg.mediaObject?.mediaBlob) {
            blob = msg.mediaObject.mediaBlob.forceToBlob();
          }
          if (!blob) return null;
          return {
            blob,
            mimetype: msg.mimetype,
            filename: msg.filename,
            filesize: msg.size
          };
        };
        window.WWebJS.arrayBufferToBase64 = (arrayBuffer) => {
          let binary = "";
          const bytes = new Uint8Array(arrayBuffer);
          const len = bytes.byteLength;
          for (let i = 0; i < len; i++) {
            binary += String.fromCharCode(bytes[i]);
          }
          return window.btoa(binary);
        };
        window.WWebJS.arrayBufferToBase64Async = (arrayBuffer) => new Promise((resolve, reject) => {
          const blob = new Blob([arrayBuffer], {
            type: "application/octet-stream"
          });
          const fileReader = new FileReader();
          fileReader.onload = () => {
            const [, data] = fileReader.result.split(",");
            resolve(data);
          };
          fileReader.onerror = (e) => reject(e);
          fileReader.readAsDataURL(blob);
        });
        window.WWebJS.getFileHash = async (data) => {
          let buffer = await data.arrayBuffer();
          const hashBuffer = await crypto.subtle.digest("SHA-256", buffer);
          return btoa(String.fromCharCode(...new Uint8Array(hashBuffer)));
        };
        window.WWebJS.generateHash = async (length) => {
          var result = "";
          var characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
          var charactersLength = characters.length;
          for (var i = 0; i < length; i++) {
            result += characters.charAt(
              Math.floor(Math.random() * charactersLength)
            );
          }
          return result;
        };
        window.WWebJS.generateWaveform = async (audioFile) => {
          try {
            const audioData = await audioFile.arrayBuffer();
            const audioContext = new AudioContext();
            const audioBuffer = await audioContext.decodeAudioData(audioData);
            const rawData = audioBuffer.getChannelData(0);
            const samples = 64;
            const blockSize = Math.floor(rawData.length / samples);
            const filteredData = [];
            for (let i = 0; i < samples; i++) {
              const blockStart = blockSize * i;
              let sum = 0;
              for (let j = 0; j < blockSize; j++) {
                sum = sum + Math.abs(rawData[blockStart + j]);
              }
              filteredData.push(sum / blockSize);
            }
            const multiplier = Math.pow(Math.max(...filteredData), -1);
            const normalizedData = filteredData.map((n) => n * multiplier);
            const waveform = new Uint8Array(
              normalizedData.map((n) => Math.floor(100 * n))
            );
            return waveform;
          } catch (ignoredError) {
            return void 0;
          }
        };
        window.WWebJS.sendClearChat = async (chatId) => {
          let chat = await window.WWebJS.getChat(chatId, { getAsModel: false });
          if (chat !== void 0) {
            await window.require("WAWebChatClearBridge").sendClear(chat, false);
            return true;
          }
          return false;
        };
        window.WWebJS.sendDeleteChat = async (chatId) => {
          let chat = await window.WWebJS.getChat(chatId, { getAsModel: false });
          if (chat !== void 0) {
            await window.require("WAWebDeleteChatAction").sendDelete(chat);
            return true;
          }
          return false;
        };
        window.WWebJS.sendChatstate = async (state, chatId) => {
          chatId = window.require("WAWebWidFactory").createWid(chatId);
          const ChatState = window.require("WAWebChatStateBridge");
          switch (state) {
            case "typing":
              await ChatState.sendChatStateComposing(chatId);
              break;
            case "recording":
              await ChatState.sendChatStateRecording(chatId);
              break;
            case "stop":
              await ChatState.sendChatStatePaused(chatId);
              break;
            default:
              throw "Invalid chatstate";
          }
          return true;
        };
        window.WWebJS.getLabelModel = (label) => {
          let res = label.serialize();
          res.hexColor = label.hexColor;
          return res;
        };
        window.WWebJS.getLabels = () => {
          const labels = window.require("WAWebCollections").Label.getModelsArray();
          return labels.map((label) => window.WWebJS.getLabelModel(label));
        };
        window.WWebJS.getLabel = (labelId) => {
          const label = window.require("WAWebCollections").Label.get(labelId);
          return window.WWebJS.getLabelModel(label);
        };
        window.WWebJS.getChatLabels = async (chatId) => {
          const chat = await window.WWebJS.getChat(chatId);
          return (chat.labels || []).map((id) => window.WWebJS.getLabel(id));
        };
        window.WWebJS.getOrderDetail = async (orderId, token, chatId) => {
          const chatWid = window.require("WAWebWidFactory").createWid(chatId);
          return window.require("WAWebBizOrderBridge").queryOrder(chatWid, orderId, 80, 80, token);
        };
        window.WWebJS.getProductMetadata = async (productId) => {
          let sellerId = window.require("WAWebConnModel").Conn.wid;
          let product = await window.require("WAWebBizProductCatalogBridge").queryProduct(sellerId, productId);
          if (product && product.data) {
            return product.data;
          }
          return void 0;
        };
        window.WWebJS.rejectCall = async (peerJid, id) => {
          let userId = window.require("WAWebUserPrefsMeUser").getMaybeMePnUser()._serialized;
          const stanza = window.require("WAWap").wap(
            "call",
            {
              id: window.require("WAWap").generateId(),
              from: userId,
              to: peerJid
            },
            [
              window.require("WAWap").wap("reject", {
                "call-id": id,
                "call-creator": peerJid,
                count: "0"
              })
            ]
          );
          await window.require("WADeprecatedSendIq").deprecatedCastStanza(stanza);
        };
        window.WWebJS.cropAndResizeImage = async (media, options = {}) => {
          if (!media.mimetype.includes("image"))
            throw new Error("Media is not an image");
          if (options.mimetype && !options.mimetype.includes("image"))
            delete options.mimetype;
          options = Object.assign(
            {
              size: 640,
              mimetype: media.mimetype,
              quality: 0.75,
              asDataUrl: false
            },
            options
          );
          const img = await new Promise((resolve, reject) => {
            const img2 = new Image();
            img2.onload = () => resolve(img2);
            img2.onerror = reject;
            img2.src = `data:${media.mimetype};base64,${media.data}`;
          });
          const sl = Math.min(img.width, img.height);
          const sx = Math.floor((img.width - sl) / 2);
          const sy = Math.floor((img.height - sl) / 2);
          const canvas = document.createElement("canvas");
          canvas.width = options.size;
          canvas.height = options.size;
          const ctx = canvas.getContext("2d");
          ctx.drawImage(img, sx, sy, sl, sl, 0, 0, options.size, options.size);
          const dataUrl = canvas.toDataURL(options.mimetype, options.quality);
          if (options.asDataUrl) return dataUrl;
          return Object.assign(media, {
            mimetype: options.mimetype,
            data: dataUrl.replace(`data:${options.mimetype};base64,`, "")
          });
        };
        window.WWebJS.setPicture = async (chatId, media) => {
          const thumbnail = await window.WWebJS.cropAndResizeImage(media, {
            asDataUrl: true,
            mimetype: "image/jpeg",
            size: 96
          });
          const profilePic = await window.WWebJS.cropAndResizeImage(media, {
            asDataUrl: true,
            mimetype: "image/jpeg",
            size: 640
          });
          const chatWid = window.require("WAWebWidFactory").createWid(chatId);
          try {
            const collection = window.require("WAWebCollections").ProfilePicThumb.get(chatId) || await window.require("WAWebCollections").ProfilePicThumb.find(chatId);
            if (!collection?.canSet()) return false;
            const res = await window.require("WAWebContactProfilePicThumbBridge").sendSetPicture(chatWid, thumbnail, profilePic);
            return res ? res.status === 200 : false;
          } catch (err) {
            if (err.name === "ServerStatusCodeError") return false;
            throw err;
          }
        };
        window.WWebJS.deletePicture = async (chatid) => {
          const chatWid = window.require("WAWebWidFactory").createWid(chatid);
          try {
            const collection = window.require("WAWebCollections").ProfilePicThumb.get(chatid);
            if (!collection.canDelete()) return;
            const res = await window.require("WAWebContactProfilePicThumbBridge").requestDeletePicture(chatWid);
            return res ? res.status === 200 : false;
          } catch (err) {
            if (err.name === "ServerStatusCodeError") return false;
            throw err;
          }
        };
        window.WWebJS.getProfilePicThumbToBase64 = async (chatWid) => {
          const profilePicCollection = await window.require("WAWebCollections").ProfilePicThumb.find(chatWid);
          const _readImageAsBase64 = (imageBlob) => {
            return new Promise((resolve) => {
              const reader = new FileReader();
              reader.onloadend = function() {
                const base64Image = reader.result;
                if (base64Image == null) {
                  resolve(void 0);
                } else {
                  const base64Data = base64Image.toString().split(",")[1];
                  resolve(base64Data);
                }
              };
              reader.readAsDataURL(imageBlob);
            });
          };
          if (profilePicCollection?.img) {
            try {
              const response2 = await fetch(profilePicCollection.img);
              if (response2.ok) {
                const imageBlob = await response2.blob();
                if (imageBlob) {
                  const base64Image = await _readImageAsBase64(imageBlob);
                  return base64Image;
                }
              }
            } catch (ignoredError) {
            }
          }
          return void 0;
        };
        window.WWebJS.getAddParticipantsRpcResult = async (groupWid, participantWid) => {
          const iqTo = window.require("WAWebWidToJid").widToGroupJid(groupWid);
          const participantArgs = [
            {
              participantJid: window.require("WAWebWidToJid").widToUserJid(participantWid)
            }
          ];
          let rpcResult, resultArgs;
          const data = {
            name: void 0,
            code: void 0,
            inviteV4Code: void 0,
            inviteV4CodeExp: void 0
          };
          try {
            rpcResult = await window.require("WASmaxGroupsAddParticipantsRPC").sendAddParticipantsRPC({ participantArgs, iqTo });
            resultArgs = rpcResult.value.addParticipant[0].addParticipantsParticipantAddedOrNonRegisteredWaUserParticipantErrorLidResponseMixinGroup.value.addParticipantsParticipantMixins;
          } catch (ignoredError) {
            data.code = 400;
            return data;
          }
          if (rpcResult.name === "AddParticipantsResponseSuccess") {
            const code = resultArgs?.value.error || "200";
            data.name = resultArgs?.name;
            data.code = +code;
            data.inviteV4Code = resultArgs?.value.addRequestCode;
            data.inviteV4CodeExp = resultArgs?.value.addRequestExpiration?.toString();
          } else if (rpcResult.name === "AddParticipantsResponseClientError") {
            const { code } = rpcResult.value.errorAddParticipantsClientErrors.value;
            data.code = +code;
          } else if (rpcResult.name === "AddParticipantsResponseServerError") {
            const { code } = rpcResult.value.errorServerErrors.value;
            data.code = +code;
          }
          return data;
        };
        window.WWebJS.membershipRequestAction = async (groupId, action, requesterIds, sleep2) => {
          const groupWid = window.require("WAWebWidFactory").createWid(groupId);
          const group = await window.require("WAWebCollections").Chat.find(groupWid);
          const toApprove = action === "Approve";
          let membershipRequests;
          let response2;
          let result = [];
          await window.require("WAWebGroupQueryJob").queryAndUpdateGroupMetadataById({ id: groupId });
          if (!requesterIds?.length) {
            membershipRequests = group.groupMetadata.membershipApprovalRequests._models.map(
              ({ id }) => id
            );
          } else {
            !Array.isArray(requesterIds) && (requesterIds = [requesterIds]);
            membershipRequests = requesterIds.map(
              (r) => window.require("WAWebWidFactory").createWid(r)
            );
          }
          if (!membershipRequests.length) return [];
          const participantArgs = membershipRequests.map((m) => ({
            participantArgs: [
              {
                participantJid: window.require("WAWebWidToJid").widToUserJid(m)
              }
            ]
          }));
          const groupJid = window.require("WAWebWidToJid").widToGroupJid(groupWid);
          const _getSleepTime = (sleep3) => {
            if (!Array.isArray(sleep3) || sleep3.length === 2 && sleep3[0] === sleep3[1]) {
              return sleep3;
            }
            if (sleep3.length === 1) {
              return sleep3[0];
            }
            sleep3[1] - sleep3[0] < 100 && (sleep3[0] = sleep3[1]) && (sleep3[1] += 100);
            return Math.floor(Math.random() * (sleep3[1] - sleep3[0] + 1)) + sleep3[0];
          };
          const membReqResCodes = {
            default: `An unknown error occupied while ${toApprove ? "approving" : "rejecting"} the participant membership request`,
            400: "ParticipantNotFoundError",
            401: "ParticipantNotAuthorizedError",
            403: "ParticipantForbiddenError",
            404: "ParticipantRequestNotFoundError",
            408: "ParticipantTemporarilyBlockedError",
            409: "ParticipantConflictError",
            412: "ParticipantParentLinkedGroupsResourceConstraintError",
            500: "ParticipantResourceConstraintError"
          };
          try {
            for (const participant of participantArgs) {
              response2 = await window.require("WASmaxGroupsMembershipRequestsActionRPC").sendMembershipRequestsActionRPC({
                iqTo: groupJid,
                [toApprove ? "approveArgs" : "rejectArgs"]: participant
              });
              if (response2.name === "MembershipRequestsActionResponseSuccess") {
                const value = toApprove ? response2.value.membershipRequestsActionApprove : response2.value.membershipRequestsActionReject;
                if (value?.participant) {
                  const [_] = value.participant.map((p) => {
                    const error = toApprove ? value.participant[0].membershipRequestsActionAcceptParticipantMixins?.value.error : value.participant[0].membershipRequestsActionRejectParticipantMixins?.value.error;
                    return {
                      requesterId: window.require("WAWebWidFactory").createWid(p.jid)._serialized,
                      ...error ? {
                        error: +error,
                        message: membReqResCodes[error] || membReqResCodes.default
                      } : {
                        message: `${toApprove ? "Approved" : "Rejected"} successfully`
                      }
                    };
                  });
                  _ && result.push(_);
                }
              } else {
                result.push({
                  requesterId: window.require("WAWebJidToWid").userJidToUserWid(
                    participant.participantArgs[0].participantJid
                  )._serialized,
                  message: "ServerStatusCodeError"
                });
              }
              sleep2 && participantArgs.length > 1 && participantArgs.indexOf(participant) !== participantArgs.length - 1 && await new Promise(
                (resolve) => setTimeout(resolve, _getSleepTime(sleep2))
              );
            }
            return result;
          } catch (ignoredError) {
            return [];
          }
        };
        window.WWebJS.subscribeToUnsubscribeFromChannel = async (channelId, action, options = {}) => {
          const channel = await window.WWebJS.getChat(channelId, {
            getAsModel: false
          });
          if (!channel || channel.newsletterMetadata.membershipType === "owner")
            return false;
          options = {
            eventSurface: 3,
            deleteLocalModels: options.deleteLocalModels ?? true
          };
          try {
            if (action === "Subscribe") {
              await window.require("WAWebNewsletterSubscribeAction").subscribeToNewsletterAction(channel, options);
            } else if (action === "Unsubscribe") {
              await window.require("WAWebNewsletterUnsubscribeAction").unsubscribeFromNewsletterAction(channel, options);
            } else return false;
            return true;
          } catch (err) {
            if (err.name === "ServerStatusCodeError") return false;
            throw err;
          }
        };
        window.WWebJS.pinUnpinMsgAction = async (msgId, action, duration) => {
          const message = window.require("WAWebCollections").Msg.get(msgId) || (await window.require("WAWebCollections").Msg.getMessagesById([msgId]))?.messages?.[0];
          if (!message) return false;
          if (typeof duration !== "number") return false;
          const originalFunction = window.require(
            "WAWebPinMsgConstants"
          ).getPinExpiryDuration;
          window.require("WAWebPinMsgConstants").getPinExpiryDuration = () => duration;
          const response2 = await window.require("WAWebSendPinMessageAction").sendPinInChatMsg(message, action, duration);
          window.require("WAWebPinMsgConstants").getPinExpiryDuration = originalFunction;
          return response2.messageSendResult === "OK";
        };
        window.WWebJS.getStatusModel = (status) => {
          const res = status.serialize();
          delete res._msgs;
          return res;
        };
        window.WWebJS.getAllStatuses = () => {
          const statuses = window.require("WAWebCollections").Status.getModelsArray();
          return statuses.map((status) => window.WWebJS.getStatusModel(status));
        };
        window.WWebJS.enforceLidAndPnRetrieval = async (userId) => {
          const wid = window.require("WAWebWidFactory").createWid(userId);
          const isLid = wid.server === "lid";
          let lid = isLid ? wid : window.require("WAWebApiContact").getCurrentLid(wid);
          let phone = isLid ? window.require("WAWebApiContact").getPhoneNumber(wid) : wid;
          if (!isLid && !lid) {
            const queryResult = await window.require("WAWebQueryExistsJob").queryWidExists(wid);
            if (!queryResult?.wid) return {};
            lid = window.require("WAWebApiContact").getCurrentLid(wid);
          }
          if (isLid && !phone) {
            const queryResult = await window.require("WAWebQueryExistsJob").queryWidExists(wid);
            if (!queryResult?.wid) return {};
            phone = window.require("WAWebApiContact").getPhoneNumber(wid);
          }
          return { lid, phone };
        };
        window.WWebJS.assertColor = (hex) => {
          let color;
          if (typeof hex === "number") {
            color = hex > 0 ? hex : 4294967295 + parseInt(hex) + 1;
          } else if (typeof hex === "string") {
            let number = hex.trim().replace("#", "");
            if (number.length <= 6) {
              number = "FF" + number.padStart(6, "0");
            }
            color = parseInt(number, 16);
          } else {
            throw "Invalid hex color";
          }
          return color;
        };
      };
    }
  });

  // ../whatsapp-web.js/src/util/Constants.js
  var require_Constants = __commonJS({
    "../whatsapp-web.js/src/util/Constants.js"(exports) {
      "use strict";
      exports.WhatsWebURL = "https://web.whatsapp.com/";
      exports.DefaultOptions = {
        puppeteer: {
          headless: true,
          defaultViewport: null
        },
        webVersion: "2.3000.1017054665",
        webVersionCache: {
          type: "local"
        },
        authTimeoutMs: 0,
        qrMaxRetries: 0,
        takeoverOnConflict: false,
        takeoverTimeoutMs: 0,
        userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/101.0.4951.67 Safari/537.36",
        ffmpegPath: "ffmpeg",
        bypassCSP: false,
        proxyAuthentication: void 0,
        pairWithPhoneNumber: {
          phoneNumber: "",
          showNotification: true,
          intervalMs: 18e4
        }
      };
      exports.Status = {
        INITIALIZING: 0,
        AUTHENTICATING: 1,
        READY: 3
      };
      exports.Events = {
        AUTHENTICATED: "authenticated",
        AUTHENTICATION_FAILURE: "auth_failure",
        READY: "ready",
        CHAT_REMOVED: "chat_removed",
        CHAT_ARCHIVED: "chat_archived",
        MESSAGE_RECEIVED: "message",
        MESSAGE_CIPHERTEXT: "message_ciphertext",
        MESSAGE_CIPHERTEXT_FAILED: "message_ciphertext_failed",
        MESSAGE_CREATE: "message_create",
        MESSAGE_REVOKED_EVERYONE: "message_revoke_everyone",
        MESSAGE_REVOKED_ME: "message_revoke_me",
        MESSAGE_ACK: "message_ack",
        MESSAGE_EDIT: "message_edit",
        UNREAD_COUNT: "unread_count",
        MESSAGE_REACTION: "message_reaction",
        MEDIA_UPLOADED: "media_uploaded",
        CONTACT_CHANGED: "contact_changed",
        GROUP_JOIN: "group_join",
        GROUP_LEAVE: "group_leave",
        GROUP_ADMIN_CHANGED: "group_admin_changed",
        GROUP_MEMBERSHIP_REQUEST: "group_membership_request",
        GROUP_UPDATE: "group_update",
        QR_RECEIVED: "qr",
        CODE_RECEIVED: "code",
        LOADING_SCREEN: "loading_screen",
        DISCONNECTED: "disconnected",
        STATE_CHANGED: "change_state",
        BATTERY_CHANGED: "change_battery",
        INCOMING_CALL: "call",
        REMOTE_SESSION_SAVED: "remote_session_saved",
        VOTE_UPDATE: "vote_update"
      };
      exports.MessageTypes = {
        TEXT: "chat",
        AUDIO: "audio",
        VOICE: "ptt",
        IMAGE: "image",
        ALBUM: "album",
        VIDEO: "video",
        DOCUMENT: "document",
        STICKER: "sticker",
        LOCATION: "location",
        CONTACT_CARD: "vcard",
        CONTACT_CARD_MULTI: "multi_vcard",
        ORDER: "order",
        REVOKED: "revoked",
        PRODUCT: "product",
        UNKNOWN: "unknown",
        GROUP_INVITE: "groups_v4_invite",
        LIST: "list",
        LIST_RESPONSE: "list_response",
        BUTTONS_RESPONSE: "buttons_response",
        PAYMENT: "payment",
        BROADCAST_NOTIFICATION: "broadcast_notification",
        CALL_LOG: "call_log",
        CIPHERTEXT: "ciphertext",
        DEBUG: "debug",
        E2E_NOTIFICATION: "e2e_notification",
        GP2: "gp2",
        GROUP_NOTIFICATION: "group_notification",
        HSM: "hsm",
        INTERACTIVE: "interactive",
        NATIVE_FLOW: "native_flow",
        NOTIFICATION: "notification",
        NOTIFICATION_TEMPLATE: "notification_template",
        OVERSIZED: "oversized",
        PROTOCOL: "protocol",
        REACTION: "reaction",
        TEMPLATE_BUTTON_REPLY: "template_button_reply",
        POLL_CREATION: "poll_creation",
        SCHEDULED_EVENT_CREATION: "scheduled_event_creation"
      };
      exports.GroupNotificationTypes = {
        ADD: "add",
        INVITE: "invite",
        REMOVE: "remove",
        LEAVE: "leave",
        PROMOTE: "promote",
        DEMOTE: "demote",
        SUBJECT: "subject",
        DESCRIPTION: "description",
        PICTURE: "picture",
        ANNOUNCE: "announce",
        RESTRICT: "restrict"
      };
      exports.ChatTypes = {
        SOLO: "solo",
        GROUP: "group",
        UNKNOWN: "unknown"
      };
      exports.WAState = {
        CONFLICT: "CONFLICT",
        CONNECTED: "CONNECTED",
        DEPRECATED_VERSION: "DEPRECATED_VERSION",
        OPENING: "OPENING",
        PAIRING: "PAIRING",
        PROXYBLOCK: "PROXYBLOCK",
        SMB_TOS_BLOCK: "SMB_TOS_BLOCK",
        TIMEOUT: "TIMEOUT",
        TOS_BLOCK: "TOS_BLOCK",
        UNLAUNCHED: "UNLAUNCHED",
        UNPAIRED: "UNPAIRED",
        UNPAIRED_IDLE: "UNPAIRED_IDLE"
      };
      exports.MessageAck = {
        ACK_ERROR: -1,
        ACK_PENDING: 0,
        ACK_SERVER: 1,
        ACK_DEVICE: 2,
        ACK_READ: 3,
        ACK_PLAYED: 4
      };
    }
  });

  // src/page/inject.js
  var import_Utils = __toESM(require_Utils(), 1);

  // src/shared/protocol.js
  var import_Constants = __toESM(require_Constants(), 1);
  var PAGE_SOURCE = "wabridge-page";
  var CONTENT_SOURCE = "wabridge-content";
  var WA_ORIGIN = "https://web.whatsapp.com";
  var ErrorCodes = Object.freeze({
    METHOD_NOT_FOUND: "METHOD_NOT_FOUND",
    PAGE_ERROR: "PAGE_ERROR",
    CHAT_NOT_FOUND: "CHAT_NOT_FOUND",
    WHATSAPP_NOT_READY: "WHATSAPP_NOT_READY",
    MEDIA_UNAVAILABLE: "MEDIA_UNAVAILABLE"
  });
  var CloseCodes = Object.freeze({
    PING_TIMEOUT: 4e3,
    DISABLED: 4403,
    UNAUTHORIZED: 4401,
    REPLACED: 4409,
    REVOKED: 4410,
    WRONG_NUMBER: 4412
  });
  var DURABLE_EVENTS = /* @__PURE__ */ new Set([
    import_Constants.Events.MESSAGE_RECEIVED,
    import_Constants.Events.MESSAGE_CREATE,
    import_Constants.Events.MESSAGE_ACK
  ]);
  var response = (id, result) => ({ type: "response", id, result });
  var errorResponse = (id, code, message) => ({
    type: "response",
    id,
    error: { code, message }
  });
  var event = (name, payload, ts) => ts === void 0 ? { type: "event", name, payload } : { type: "event", name, payload, ts };
  var isEnvelope = (x) => Boolean(x) && typeof x === "object" && typeof x.type === "string";

  // src/page/bridge.js
  function createBridge({ win, methods, origin = WA_ORIGIN }) {
    const post = (frame) => {
      const data = { source: PAGE_SOURCE, frame };
      try {
        win.postMessage(data, origin);
      } catch (err) {
        if (err?.name !== "DataCloneError") throw err;
        win.postMessage(JSON.parse(JSON.stringify(data)), origin);
      }
    };
    const emit = (name, payload) => post(event(name, payload, Date.now()));
    async function handle(frame) {
      const fn = methods.get(frame.method);
      if (!fn) {
        post(
          errorResponse(
            frame.id,
            ErrorCodes.METHOD_NOT_FOUND,
            `unknown method: ${frame.method}`
          )
        );
        return;
      }
      try {
        const result = await fn(frame.params ?? {});
        post(response(frame.id, result === void 0 ? null : result));
      } catch (err) {
        post(
          errorResponse(
            frame.id,
            err?.code || ErrorCodes.PAGE_ERROR,
            err?.message || String(err)
          )
        );
      }
    }
    const listener = (ev) => {
      if (ev.source !== win) return;
      const data = ev.data;
      if (!data || typeof data !== "object" || data.source !== CONTENT_SOURCE)
        return;
      if (!isEnvelope(data.frame) || data.frame.type !== "request") return;
      handle(data.frame);
    };
    win.addEventListener("message", listener);
    return {
      emit,
      post,
      dispose: () => win.removeEventListener("message", listener)
    };
  }

  // src/page/model.js
  var serializedOf = (wid) => wid && typeof wid === "object" ? wid._serialized : wid;
  function normalizeMessageModel(model) {
    if (!model || typeof model !== "object" || !model.id || typeof model.id !== "object")
      return model;
    const id = model.id;
    if (typeof id._serialized === "string" && id._serialized) return model;
    const mangled = Object.entries(id).find(
      ([key, value]) => key !== "_serialized" && typeof value === "string" && /^(true|false)_.+_[0-9A-F]+/i.test(value)
    );
    if (mangled) {
      id._serialized = mangled[1];
      return model;
    }
    const remote = serializedOf(id.remote);
    if (remote && id.id !== void 0) {
      const participant = serializedOf(id.participant);
      id._serialized = `${Boolean(id.fromMe)}_${remote}_${id.id}${participant ? `_${participant}` : ""}`;
    }
    return model;
  }

  // src/page/methods.js
  var CAPTURE_WAIT_MS = 2e3;
  var CAPTURE_POLL_MS = 100;
  function codedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
  }
  var remoteOf = (m) => m.id?.remote?._serialized ?? m.id?.remote;
  async function waitForCaptured(captured, chat, content) {
    const chatId = chat.id?._serialized;
    const exact = (m) => m.body === content && remoteOf(m) === chatId;
    const loose = (m) => m.body === content;
    for (let waited = 0; waited <= CAPTURE_WAIT_MS; waited += CAPTURE_POLL_MS) {
      const newestFirst = [...captured].reverse();
      const hit = newestFirst.find(exact) ?? newestFirst.find(loose);
      if (hit) return hit;
      await new Promise((resolve) => setTimeout(resolve, CAPTURE_POLL_MS));
    }
    return null;
  }
  function createMethods({ getInfo }) {
    const methods = /* @__PURE__ */ new Map();
    methods.set(
      "getState",
      async () => window.require("WAWebSocketModel").Socket.state
    );
    methods.set("getInfo", async () => getInfo());
    methods.set(
      "sendMessage",
      async ({ chatId, content, options = {}, sendSeen = false }) => {
        if (typeof window.WWebJS === "undefined") {
          throw codedError(
            ErrorCodes.WHATSAPP_NOT_READY,
            "WWebJS helpers are not loaded yet"
          );
        }
        if (!chatId || typeof content !== "string") {
          throw codedError(
            ErrorCodes.PAGE_ERROR,
            "chatId and string content are required"
          );
        }
        const chat = await window.WWebJS.getChat(chatId, {
          getAsModel: false
        });
        if (!chat)
          throw codedError(
            ErrorCodes.CHAT_NOT_FOUND,
            `Chat not found: ${chatId}`
          );
        if (sendSeen) await window.WWebJS.sendSeen(chatId);
        const { Msg } = window.require("WAWebCollections");
        const captured = [];
        const capture = (m) => {
          if (m?.id?.fromMe) captured.push(m);
        };
        Msg.on("add", capture);
        let msg;
        try {
          msg = await window.WWebJS.sendMessage(chat, content, options);
          if (!msg) {
            msg = await waitForCaptured(captured, chat, content);
            if (msg) {
              console.info(
                "[wabridge] sendMessage: key lookup missed, used captured message",
                msg.id?._serialized
              );
            } else {
              console.warn(
                "[wabridge] sendMessage: no message model found after send to",
                chatId
              );
            }
          }
        } finally {
          Msg.off("add", capture);
        }
        return msg ? normalizeMessageModel(window.WWebJS.getMessageModel(msg)) : null;
      }
    );
    methods.set("getNumberId", async ({ phone } = {}) => {
      if (typeof window.WWebJS === "undefined") {
        throw codedError(
          ErrorCodes.WHATSAPP_NOT_READY,
          "WWebJS helpers are not loaded yet"
        );
      }
      if (typeof phone !== "string" || phone === "") {
        throw codedError(
          ErrorCodes.PAGE_ERROR,
          "phone must be a non-empty string"
        );
      }
      const id = phone.endsWith("@c.us") ? phone : `${phone}@c.us`;
      const wid = window.require("WAWebWidFactory").createWid(id);
      const result = await window.require("WAWebQueryExistsJob").queryWidExists(wid);
      if (!result || result.wid === void 0) return null;
      let lid = null;
      try {
        lid = window.require("WAWebApiContact").getCurrentLid(result.wid)?._serialized ?? null;
      } catch {
      }
      return { phone: result.wid._serialized, lid };
    });
    methods.set("downloadMedia", async ({ messageId } = {}) => {
      if (typeof window.WWebJS === "undefined") {
        throw codedError(
          ErrorCodes.WHATSAPP_NOT_READY,
          "WWebJS helpers are not loaded yet"
        );
      }
      if (typeof messageId !== "string" || messageId === "") {
        throw codedError(
          ErrorCodes.PAGE_ERROR,
          "messageId must be a non-empty string"
        );
      }
      const resolved = await window.WWebJS.resolveMediaBlob(messageId);
      if (!resolved) {
        throw codedError(
          ErrorCodes.MEDIA_UNAVAILABLE,
          "Media is no longer available on the phone"
        );
      }
      const data = await window.WWebJS.arrayBufferToBase64Async(
        await resolved.blob.arrayBuffer()
      );
      return {
        data,
        mimetype: resolved.mimetype,
        filename: resolved.filename,
        filesize: resolved.filesize
      };
    });
    return methods;
  }

  // src/page/events.js
  function attachEvents(emit) {
    const { Msg } = window.require("WAWebCollections");
    const AppState = window.require("WAWebSocketModel").Socket;
    const model = (msg) => normalizeMessageModel(window.WWebJS.getMessageModel(msg));
    const safe = (name, fn) => (...args) => {
      try {
        fn(...args);
      } catch (err) {
        console.error("[wabridge] event handler failed:", name, err);
      }
    };
    const registered = [];
    const on = (target, name, handler) => {
      target.on(name, handler);
      registered.push([target, name, handler]);
    };
    on(
      Msg,
      "add",
      safe("Msg.add", (msg) => {
        if (!msg.isNewMsg) return;
        if (msg.type === "ciphertext") return;
        const m = model(msg);
        emit(import_Constants.Events.MESSAGE_CREATE, m);
        if (!m.id.fromMe) emit(import_Constants.Events.MESSAGE_RECEIVED, m);
      })
    );
    on(
      Msg,
      "change:ack",
      safe("Msg.change:ack", (msg, ack) => {
        emit(import_Constants.Events.MESSAGE_ACK, { message: model(msg), ack });
      })
    );
    on(
      AppState,
      "change:state",
      safe("AppState.change:state", (_appState, state) => {
        emit(import_Constants.Events.STATE_CHANGED, { state });
      })
    );
    window._wwjsListeners = registered;
    return () => {
      for (const [target, name, handler] of registered)
        target.off(name, handler);
      registered.length = 0;
      window._wwjsListeners = [];
    };
  }

  // src/page/inject.js
  var POLL_MS = 500;
  var REINJECT_CHECK_MS = 2e3;
  var TAG = "[wabridge]";
  var BUILD = true ? "5579b1509e4e" : "dev";
  var sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  async function waitFor(predicate) {
    for (; ; ) {
      try {
        if (predicate()) return;
      } catch {
      }
      await sleep(POLL_MS);
    }
  }
  var socket = () => window.require("WAWebSocketModel").Socket;
  function buildInfo() {
    const me = window.require("WAWebUserPrefsMeUser");
    return {
      ...window.require("WAWebConnModel").Conn.serialize(),
      wid: me.getMaybeMePnUser() || me.getMaybeMeLidUser(),
      // the phone-number id, so the server can confirm the number even when wid is a privacy id
      pnWid: me.getMaybeMePnUser()?._serialized ?? null,
      waVersion: window.Debug.VERSION
    };
  }
  async function main() {
    const existing = window.__wabridge;
    if (existing && existing.build === BUILD) {
      existing.announce();
      return;
    }
    if (existing) {
      console.info(
        TAG,
        "replacing page script build",
        existing.build,
        "->",
        BUILD
      );
      try {
        existing.dispose?.();
      } catch (err) {
        console.warn(TAG, "old instance dispose failed", err);
      }
    }
    const ctl = {
      build: BUILD,
      ready: false,
      info: null,
      emit: null,
      dispose: null,
      announce() {
        if (this.ready && this.emit) {
          this.emit(import_Constants.Events.READY, {
            ...this.info,
            state: socket().state
          });
        }
      }
    };
    window.__wabridge = ctl;
    const bridge = createBridge({
      win: window,
      methods: createMethods({ getInfo: () => ctl.info })
    });
    ctl.emit = bridge.emit;
    await waitFor(() => window.Debug?.VERSION != void 0);
    await waitFor(() => socket().hasSynced === true);
    let detach = setup();
    const watcher = setInterval(() => {
      if (typeof window.WWebJS === "undefined") {
        console.info(TAG, "WWebJS missing, re-injecting");
        detach();
        detach = setup();
      }
    }, REINJECT_CHECK_MS);
    ctl.dispose = () => {
      clearInterval(watcher);
      detach();
      bridge.dispose();
      ctl.ready = false;
    };
    function setup() {
      if (typeof window.WWebJS === "undefined") (0, import_Utils.LoadUtils)();
      ctl.info = buildInfo();
      const off = attachEvents(bridge.emit);
      ctl.ready = true;
      ctl.announce();
      console.info(
        TAG,
        "ready (server-delivered)",
        ctl.info.wid?._serialized,
        window.Debug.VERSION,
        "build",
        BUILD
      );
      return off;
    }
  }
  main().catch((err) => console.error(TAG, "initialisation failed", err));
})();
