(ns game.cards.identities
  (:require [game.core :refer :all]
            [game.core.eid :refer [effect-completed make-eid complete-with-result]]
            [game.core.card-defs :refer [card-def]]
            [game.core.prompts :refer [show-wait-prompt clear-wait-prompt]]
            [game.core.toasts :refer [toast]]
            [game.core.card :refer :all]
            [game.utils :refer :all]
            [game.macros :refer [effect req msg wait-for continue-ability]]
            [clojure.string :refer [split-lines split join lower-case includes? starts-with?]]
            [clojure.stacktrace :refer [print-stack-trace]]
            [jinteki.utils :refer :all]))

;;; Helper functions for Draft cards
(def draft-points-target
  "Set each side's agenda points target at 6, per draft format rules"
  (req (swap! state assoc-in [:runner :agenda-point-req] 6)
       (swap! state assoc-in [:corp :agenda-point-req] 6)))

(defn- has-most-faction?
  "Checks if the faction has a plurality of rezzed / installed cards"
  [state side fc]
  (let [card-list (all-active-installed state side)
        faction-freq (frequencies (map :faction card-list))
        reducer (fn [{:keys [max-count] :as acc} faction count]
                  (cond
                    ;; Has plurality update best-faction
                    (> count max-count)
                    {:max-count count :max-faction faction}
                    ;; Lost plurality
                    (= count max-count)
                    (dissoc acc :max-faction)
                    ;; Count is not more, do not change the accumulator map
                    :default
                    acc))
        best-faction (:max-faction (reduce-kv reducer {:max-count 0 :max-faction nil} faction-freq))]
    (= fc best-faction)))

;; Card definitions
(def card-definitions
  {"419: Amoral Scammer"
   {:events [{:event :corp-install
              :async true
              :req (req (and (first-event? state :corp :corp-install)
                             (pos? (:turn @state))
                             (not (rezzed? target))
                             (not (#{:rezzed-no-cost :rezzed-no-rez-cost :rezzed :faceup} (second targets)))))
              :effect
              (req (show-wait-prompt state :corp "Runner to use 419: Amoral Scammer")
                   (let [itarget target]
                     (continue-ability
                       state side
                       {:optional
                        {:prompt "Expose installed card unless Corp pays 1 [Credits]?"
                         :player :runner
                         :autoresolve (get-autoresolve :auto-419)
                         :no-ability {:effect (req (clear-wait-prompt state :corp))}
                         :yes-ability
                         {:async true
                          :effect (req (clear-wait-prompt state :corp)
                                       (if (not (can-pay? state :corp eid card nil :credit 1))
                                         (do
                                           (toast state :corp "Cannot afford to pay 1 credit to block card exposure" "info")
                                           (expose state :runner eid itarget))
                                         (do
                                           (show-wait-prompt state :runner "Corp decision")
                                           (continue-ability
                                             state side
                                             {:optional
                                              {:prompt "Pay 1 [Credits] to prevent exposure of installed card?"
                                               :player :corp
                                               :no-ability
                                               {:async true
                                                :effect (req (expose state :runner eid itarget)
                                                             (clear-wait-prompt state :runner))}
                                               :yes-ability
                                               {:effect (req (pay state :corp card [:credit 1])
                                                             (system-msg state :corp (str "spends 1 [Credits] to prevent "
                                                                                          " card from being exposed"))
                                                             (clear-wait-prompt state :runner))}}}
                                             card nil))))}}}
                       card nil)))}]
    :abilities [(set-autoresolve :auto-419 "419")]}

   "Acme Consulting: The Truth You Need"
   (letfn [(activate [state card active]
             (update! state :corp (assoc-in card [:special :acme-active] active))
             (swap! state update-in [:runner :tag :additional] (if active inc dec))
             (trigger-event state :corp :runner-additional-tag-change (if active 1 -1)))
           (outermost? [run-position run-ices]
             (and run-position
                  (pos? run-position)
                  (= run-position (count run-ices))))]
     {:implementation "Additional tag is gained on approach, not on encounter"
      ;; Should be comprehensive list of all cases when tag should be gained or lost
      :events [{:event :run
                :effect (req (when (and (outermost? run-position run-ices)
                                        (rezzed? current-ice))
                               (activate state card true)))}
               {:event :rez
                :effect (req (when (and (outermost? run-position run-ices)
                                        (same-card? current-ice target))
                               (activate state card true)))}
               {:event :derez
                :effect (req (when (outermost? run-position run-ices)
                               (activate state card false)))}
               {:event :pass-ice
                :effect (req (when (and (outermost? run-position run-ices)
                                        (get-in card [:special :acme-active]))
                               (activate state card false)))}
               {:event :run-ends
                :effect (req (when (get-in card [:special :acme-active])
                               (activate state card false)))}]})

   "Adam: Compulsive Hacker"
   {:events [{:event :pre-start-game
              :req (req (= side :runner))
              :async true
              :effect (req (show-wait-prompt state :corp "Runner to choose starting directives")
                           (let [directives (->> (server-cards)
                                                 (filter #(has-subtype? % "Directive"))
                                                 (map make-card)
                                                 (zone :play-area))]
                             ;; Add directives to :play-area - assumed to be empty
                             (swap! state assoc-in [:runner :play-area] directives)
                             (continue-ability state side
                                               {:prompt (str "Choose 3 starting directives")
                                                :choices {:max 3
                                                          :all true
                                                          :card #(and (runner? %)
                                                                      (in-play-area? %))}
                                                :effect (req (doseq [c targets]
                                                               (runner-install state side c {:ignore-all-cost true
                                                                                             :custom-message (str "starts with " (:title c) " in play")}))
                                                             (swap! state assoc-in [:runner :play-area] [])
                                                             (clear-wait-prompt state :corp))}
                                               card nil)))}]}

   "AgInfusion: New Miracles for a New World"
   {:abilities [{:once :per-turn
                 :req (req (and (:run @state) (not (rezzed? current-ice)) (can-rez? state side current-ice {:ignore-unique true})))
                 :prompt "Choose another server and redirect the run to its outermost position"
                 :choices (req (cancellable (remove #{(-> @state :run :server central->name)} servers)))
                 :label "Trash a piece of ice to choose another server- the runner is now running that server"
                 :msg (msg "trash the approached ICE. The Runner is now running on " target)
                 :effect (req (let [dest (server->zone state target)]
                                (trash state side current-ice)
                                (swap! state update-in [:run]
                                       #(assoc % :position (count (get-in corp (conj dest :ices)))
                                                 :server (rest dest)))))}]}

   "Akiko Nisei: Head Case"
   {:events [{:event :pre-access
              :req (req (= target :rd))
              :interactive (req true)
              :psi {:player :runner
                    :equal {:msg "access 1 additional card"
                            :effect (effect (access-bonus :rd 1)
                                            (effect-completed eid))}}}]}

   "Alice Merchant: Clan Agitator"
   {:events [{:event :successful-run
              :async true
              :interactive (req true)
              :req (req (and (= target :archives)
                             (first-successful-run-on-server? state :archives)
                             (not-empty (:hand corp))))
              :effect (effect (show-wait-prompt :runner "Corp to trash 1 card from HQ")
                              (continue-ability
                                {:prompt "Choose a card in HQ to discard"
                                 :player :corp
                                 :choices (req (:hand corp))
                                 :msg "force the Corp to trash 1 card from HQ"
                                 :effect (effect (trash :corp target)
                                                 (clear-wait-prompt :runner))}
                                card nil))}]}

   "Andromeda: Dispossessed Ristie"
   {:events [{:event :pre-start-game
              :req (req (= side :runner))
              :effect (effect (draw 4 {:suppress-event true}))}]
    :mulligan (effect (draw 4 {:suppress-event true}))}

   "Apex: Invasive Predator"
   (let [ability {:prompt "Select a card to install facedown"
                  :label "Install a card facedown (start of turn)"
                  :once :per-turn
                  :choices {:max 1
                            :card #(and (runner? %)
                                        (in-hand? %))}
                  :req (req (and (pos? (count (:hand runner)))
                                 (:runner-phase-12 @state)))
                  :async true
                  :effect (effect (runner-install (assoc eid :source card :source-type :runner-install) target {:facedown true}))}]
     {:implementation "Install restriction not enforced"
      :events [(assoc ability :event :runner-turn-begins)]
      :flags {:runner-phase-12 (req true)}
      :abilities [ability]})

   "Argus Security: Protection Guaranteed"
   {:events [{:event :agenda-stolen
              :prompt "Take 1 tag or suffer 2 meat damage?"
              :async true
              :choices ["1 tag" "2 meat damage"] :player :runner
              :msg "make the Runner take 1 tag or suffer 2 meat damage"
              :effect (req (if (= target "1 tag")
                             (do (system-msg state side "chooses to take 1 tag")
                                 (gain-tags state :runner eid 1))
                             (do (system-msg state side "chooses to suffer 2 meat damage")
                                 (damage state :runner eid :meat 2 {:unboostable true :card card}))))}]}

   "Armand \"Geist\" Walker: Tech Lord"
   {:events [{:event :runner-trash
              :async true
              :interactive (req true)
              :req (req (and (= side :runner) (= (second targets) :ability-cost)))
              :msg "draw a card"
              :effect (effect (draw eid 1 nil))}]}

   "Asa Group: Security Through Vigilance"
   {:events [{:event :corp-install
              :async true
              :req (req (first-event? state :corp :corp-install))
              :effect (req (let [installed-card target
                                 z (butlast (:zone installed-card))]
                             (continue-ability
                               state side
                               {:prompt (str "Select a "
                                             (if (is-remote? z)
                                               "non-agenda"
                                               "piece of ice")
                                             " in HQ to install with Asa Group: Security Through Vigilance (optional)")
                                :choices {:card #(and (in-hand? %)
                                                      (corp? %)
                                                      (corp-installable-type? %)
                                                      (not (agenda? %))
                                                      (or (is-remote? z)
                                                          (ice? %)))}
                                :async true
                                :effect (effect (corp-install eid target (zone->name z) nil))}
                               card nil)))}]}

   "Ayla \"Bios\" Rahim: Simulant Specialist"
   {:abilities [{:label "[:click] Add 1 card from NVRAM to your grip"
                 :cost [:click 1]
                 :async true
                 :prompt "Choose a card from NVRAM"
                 :choices (req (cancellable (:hosted card)))
                 :msg "move a card from NVRAM to their Grip"
                 :effect (effect (move target :hand)
                                 (effect-completed eid))}]
    :events [{:event :pre-start-game
              :req (req (= side :runner))
              :async true
              :effect (req (show-wait-prompt state :corp "the Runner to choose cards for NVRAM")
                           (doseq [c (take 6 (:deck runner))]
                             (move state side c :play-area))
                           (continue-ability
                             state side
                             {:prompt (str "Select 4 cards for NVRAM")
                              :async true
                              :choices {:max 4
                                        :all true
                                        :card #(and (runner? %)
                                                    (in-play-area? %))}
                              :effect (req (doseq [c targets]
                                             (host state side (get-card state card) c {:facedown true}))
                                           (doseq [c (get-in @state [:runner :play-area])]
                                             (move state side c :deck))
                                           (shuffle! state side :deck)
                                           (clear-wait-prompt state :corp)
                                           (effect-completed state side eid))}
                             card nil))}]}

   "Azmari EdTech: Shaping the Future"
   (let [choose-type {:prompt "Name a Runner card type"
                      :choices ["Event" "Resource" "Program" "Hardware"]
                      :effect (effect (update! (assoc card :az-target target))
                                      (system-msg (str "uses Azmari EdTech: Shaping the Future to name " target)))}
         check-type {:req (req (is-type? target (:az-target card)))
                     :effect (effect (gain-credits :corp 2))
                     :once :per-turn
                     :msg (msg "gain 2 [Credits] from " (:az-target card))}]
     {:events [(assoc choose-type :event :corp-turn-ends)
               (assoc check-type
                      :event :runner-install
                      :req (req (and (is-type? target (:az-target card))
                                     (not (facedown? target)))))
               (assoc check-type :event :play-event)]})

   "Az McCaffrey: Mechanical Prodigy"
   ;; Effect marks Az's ability as "used" if it has already met it's trigger condition this turn
   (letfn [(az-type? [card] (or (hardware? card)
                                (and (resource? card)
                                     (or (has-subtype? card "Job")
                                         (has-subtype? card "Connection")))))
           (not-triggered? [state card] (not (get-in @state [:per-turn (:cid card)])))
           (mark-triggered [state card] (swap! state assoc-in [:per-turn (:cid card)] true))]
     {:effect (req (when (pos? (event-count state :runner :runner-install #(az-type? (first %))))
                     (mark-triggered state card)))
      :constant-effects [{:type :install-cost
                          :req (req (and (az-type? target)
                                         (not-triggered? state card)))
                          :value -1}]
      :events [{:event :runner-install
                :req (req (and (az-type? target)
                               (not-triggered? state card)))
                :silent (req true)
                :msg (msg "reduce the install cost of " (:title target) " by 1 [Credits]")
                :effect (req (mark-triggered state card))}]})

   "Blue Sun: Powering the Future"
   {:flags {:corp-phase-12 (req (and (not (:disabled card))
                                     (some rezzed? (all-installed state :corp))))}
    :abilities [{:choices {:card rezzed?}
                 :label "Add 1 rezzed card to HQ and gain credits equal to its rez cost"
                 :msg (msg "add " (:title target) " to HQ and gain " (rez-cost state side target) " [Credits]")
                 :effect (effect (gain-credits (rez-cost state side target))
                                 (move target :hand))}]}

   "Boris \"Syfr\" Kovac: Crafty Veteran"
   {:events [{:event :pre-start-game
              :effect draft-points-target}
             {:event :runner-turn-begins
              :req (req (and (has-most-faction? state :runner "Criminal")
                             (pos? (get-in runner [:tag :base]))))
              :msg "remove 1 tag"
              :effect (effect (lose-tags 1))}]}

   "Cerebral Imaging: Infinite Frontiers"
   {:effect (req (when (> (:turn @state) 1)
                   (swap! state assoc-in [:corp :hand-size :base] (:credit corp)))
                 (add-watch state :cerebral-imaging
                            (fn [k ref old new]
                              (let [credit (get-in new [:corp :credit])]
                                (when (not= (get-in old [:corp :credit]) credit)
                                  (swap! ref assoc-in [:corp :hand-size :base] credit))))))
    :leave-play (req (remove-watch state :cerebral-imaging)
                     (swap! state assoc-in [:corp :hand-size :base] 5))}

   "Chaos Theory: Wünderkind"
   {:effect (effect (gain :memory 1))
    :leave-play (effect (lose :runner :memory 1))}

   "Chronos Protocol: Selective Mind-mapping"
   {:req (req (empty? (filter #(= :net (first %)) (turn-events state :runner :damage))))
    :effect (effect (enable-corp-damage-choice))
    :leave-play (req (swap! state update-in [:damage] dissoc :damage-choose-corp))
    :events [{:event :corp-phase-12
              :effect (effect (enable-corp-damage-choice))}
             {:event :runner-phase-12
              :effect (effect (enable-corp-damage-choice))}
             {:event :pre-resolve-damage
              :async true
              :req (req (and (= target :net)
                             (corp-can-choose-damage? state)
                             (pos? (last targets))
                             (empty? (filter #(= :net (first %)) (turn-events state :runner :damage)))))
              :effect (req (show-wait-prompt state :runner "Corp to use Chronos Protocol: Selective Mind-mapping")
                           (continue-ability
                             state :corp
                             {:optional
                              {:prompt "Use Chronos Protocol to select the first card trashed?"
                               :yes-ability
                               {:prompt "Select a card to trash"
                                :choices (req (:hand runner))
                                :not-distinct true
                                :msg (msg "choose " (:title target) " to trash")
                                :effect (req (clear-wait-prompt state :runner)
                                             (chosen-damage state :corp targets))}
                               :no-ability {:effect (req (clear-wait-prompt state :runner)
                                                         (system-msg state :corp "doesn't use Chronos Protocol to select the first card trashed"))}}}
                             card nil))}]}

   "Cybernetics Division: Humanity Upgraded"
   {:effect (effect (lose :hand-size 1)
                    (lose :runner :hand-size 1))
    :leave-play (effect (gain :hand-size 1)
                        (gain :runner :hand-size 1))}

   "Earth Station: On the Grid"
   {:events [{:event :pre-first-turn
              :req (req (= side :corp))
              :effect (effect (update! (assoc card :flipped false)))}]
    :abilities [{:msg "flip their ID"
                 :effect (effect (update! (if (:flipped card)
                                            (assoc card
                                                   :flipped false
                                                   :code (subs (:code card) 0 5))
                                            (assoc card
                                                   :flipped true
                                                   :code (str (subs (:code card) 0 5) "flip")))))}]}

   "Edward Kim: Humanity's Hammer"
   {:events [{:event :access
              :once :per-turn
              :req (req (and (operation? target)
                             (turn-flag? state side card :can-trash-operation)))
              :effect (req (trash state side target)
                           (swap! state assoc-in [:run :did-trash] true)
                           (swap! state assoc-in [:runner :register :trashed-card] true)
                           (register-turn-flag! state side card :can-trash-operation (constantly false)))
              :msg (msg "trash " (:title target))}
             {:event :successful-run-ends
              :req (req (and (= (:server target) [:archives])
                             (empty? (filter :replace-access (:run-effects target)))
                             (not= (:max-access target) 0)
                             (seq (filter operation? (:discard corp)))))
              :effect (effect (register-turn-flag! card :can-trash-operation (constantly false)))}]}

   "Ele \"Smoke\" Scovak: Cynosure of the Net"
   {:recurring 1
    :interactions {:pay-credits {:req (req (and (= :ability (:source-type eid))
                                                (has-subtype? target "Icebreaker")))
                                 :type :recurring}}}

   "Exile: Streethawk"
   {:flags {:runner-install-draw true}
    :events [{:event :runner-install
              :silent (req (not (and (program? target)
                                     (some #{:discard} (:previous-zone target)))))
              :async true
              :req (req (and (program? target)
                             (some #{:discard} (:previous-zone target))))
              :msg (msg "draw a card")
              :effect (req (draw state side eid 1 nil))}]}

   "Freedom Khumalo: Crypto-Anarchist"
   {:interactions
    {:access-ability
     {:async true
      :once :per-turn
      :label "Trash card"
      :req (req (and (not (:disabled card))
                     (not (agenda? target))
                     (<= (:cost target)
                         (reduce + (map #(get-counters % :virus)
                                        (all-installed state :runner))))))
      :effect (req (let [accessed-card target
                         play-or-rez (:cost target)]
                     (show-wait-prompt state :corp "Runner to use Freedom Khumalo's ability")
                     (if (zero? play-or-rez)
                       (continue-ability state side
                                         {:async true
                                          :msg (msg "trash " (:title accessed-card) " at no cost")
                                          :effect (effect (clear-wait-prompt :corp)
                                                          (trash eid (assoc accessed-card :seen true) nil))}
                                         card nil)
                       (wait-for (resolve-ability state side (pick-virus-counters-to-spend play-or-rez) card nil)
                                 (do (clear-wait-prompt state :corp)
                                     (if-let [msg (:msg async-result)]
                                       (do (system-msg state :runner
                                                       (str "uses Freedom Khumalo: Crypto-Anarchist to"
                                                            " trash " (:title accessed-card)
                                                            " at no cost, spending " msg))
                                           (trash state side eid (assoc accessed-card :seen true) nil))
                                       ;; Player cancelled ability
                                       (do (swap! state dissoc-in [:per-turn (:cid card)])
                                           (access-non-agenda state side eid accessed-card :skip-trigger-event true))))))))}}}

   "Fringe Applications: Tomorrow, Today"
   {:events [{:event :pre-start-game
              :effect draft-points-target}
             {:event :runner-turn-begins
              :player :corp
              :req (req (and (not (:disabled card))
                             (has-most-faction? state :corp "Weyland Consortium")
                             (some ice? (all-installed state side))))
              :prompt "Select a piece of ICE to place 1 advancement token on"
              :choices {:card #(and (installed? %)
                                    (ice? %))}
              :msg (msg "place 1 advancement token on " (card-str state target))
              :effect (req (add-prop state :corp target :advance-counter 1 {:placed true}))}]}

   "Gabriel Santiago: Consummate Professional"
   {:events [{:event :successful-run
              :silent (req true)
              :req (req (and (= target :hq)
                             (first-successful-run-on-server? state :hq)))
              :msg "gain 2 [Credits]"
              :effect (effect (gain-credits 2))}]}

   "Gagarin Deep Space: Expanding the Horizon"
   {:events [{:event :pre-access-card
              :req (req (is-remote? (second (:zone target))))
              :effect (effect (access-cost-bonus [:credit 1]))
              :msg "make the Runner spend 1 [Credits] to access"}]}

   "GRNDL: Power Unleashed"
   {:events [{:event :pre-start-game
              :req (req (= :corp side))
              :effect (req (gain-credits state :corp 5)
                           (when (zero? (count-bad-pub state))
                             (gain-bad-publicity state :corp 1)))}]}

   "Haarpsichord Studios: Entertainment Unleashed"
   (let [haarp (fn [state side card]
                 (if (agenda? card)
                   ((constantly false)
                    (toast state :runner "Cannot steal due to Haarpsichord Studios." "warning"))
                   true))]
     {:events [{:event :agenda-stolen
                :effect (effect (register-turn-flag! card :can-steal haarp))}]
      :effect (req (when-not (first-event? state side :agenda-stolen)
                     (register-turn-flag! state side card :can-steal haarp)))
      :leave-play (effect (clear-turn-flag! card :can-steal))})

   "Haas-Bioroid: Architects of Tomorrow"
   {:events [{:event :pass-ice
              :async true
              :once :per-turn
              :req (req (and (rezzed? target)
                             (has-subtype? target "Bioroid")
                             (empty? (filter #(and (rezzed? %) (has-subtype? % "Bioroid"))
                                             (turn-events state side :pass-ice)))))
              :effect (effect (show-wait-prompt :runner "Corp to use Haas-Bioroid: Architects of Tomorrow")
                              (continue-ability
                                {:prompt "Select a Bioroid to rez"
                                 :player :corp
                                 :choices
                                 {:req (req (and (has-subtype? target "Bioroid")
                                                 (not (rezzed? target))
                                                 (can-pay? state side eid card nil
                                                           [:credit (rez-cost state side target {:cost-bonus -4})])))}
                                 :msg (msg "rez " (:title target))
                                 :cancel-effect (effect (clear-wait-prompt :runner)
                                                        (effect-completed eid))
                                 :effect (effect (clear-wait-prompt :runner)
                                                 (rez eid target {:cost-bonus -4}))}
                                card nil))}]}

   "Haas-Bioroid: Engineering the Future"
   {:events [{:event :corp-install
              :req (req (first-event? state corp :corp-install))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits 1))}]}

   "Haas-Bioroid: Stronger Together"
   {:constant-effects [{:type :ice-strength
                        :req (req (has-subtype? target "Bioroid"))
                        :value 1}]
    :leave-play (effect (update-all-ice))
    :effect (effect (update-all-ice))}

   "Harishchandra Ent.: Where You're the Star"
   (let [ab {:effect (req (if (is-tagged? state)
                            (reveal-hand state :runner)
                            (conceal-hand state :runner)))}]
     {:events [(assoc ab :event :runner-gain-tag)
               (assoc ab :event :runner-lose-tag)
               ;; Triggered when the runner clicks the '+' and '-' buttons
               (assoc ab :event :manual-gain-tag)
               (assoc ab :event :manual-lose-tag)
               ;; Triggered when Paparazzi enters / leaves
               (assoc ab :event :runner-is-tagged)
               ;; Triggered when gaining or losing additional tag
               (assoc ab :event :runner-additional-tag-change)]
      :effect (req (when (is-tagged? state)
                     (reveal-hand state :runner)))
      :leave-play (req (when (is-tagged? state)
                         (conceal-hand state :runner)))})

   "Harmony Medtech: Biomedical Pioneer"
   {:effect (effect (lose :agenda-point-req 1) (lose :runner :agenda-point-req 1))
    :leave-play (effect (gain :agenda-point-req 1) (gain :runner :agenda-point-req 1))}

   "Hayley Kaplan: Universal Scholar"
   {:events [{:event :runner-install
              :silent (req (not (and (first-event? state side :runner-install)
                                     (some #(is-type? % (:type target)) (:hand runner)))))
              :req (req (and (first-event? state side :runner-install)
                             (not (:facedown target))))
              :once :per-turn
              :async true
              :effect
              (req (let [itarget target
                         card-type (:type itarget)]
                     (show-wait-prompt state :corp "Runner to use Hayley's ability")
                     (continue-ability
                       state side
                       (if (some #(is-type? % (:type itarget)) (:hand runner))
                         {:optional
                          {:prompt (msg "Install another " card-type " from your Grip?")
                           :yes-ability
                           {:prompt (msg "Select another " card-type " to install from your Grip")
                            :choices {:card #(and (is-type? % card-type)
                                                  (in-hand? %))}
                            :msg (msg "install " (:title target))
                            :async true
                            :effect (effect (runner-install (assoc eid :source card :source-type :runner-install) target nil))}
                           :end-effect (effect (clear-wait-prompt :corp))}}
                         {:prompt (str "You have no " card-type "s in hand")
                          :choices ["Carry on!"]
                          :prompt-type :bogus
                          :effect (effect (clear-wait-prompt :corp))})
                       card nil)))}]}

   "Hoshiko Shiro"
   {:events [{:event :pre-first-turn
              :req (req (= side :runner))
              :effect (effect (update! (assoc card :flipped false)))}]
    :abilities [{:msg "flip their ID"
                 :effect (effect (update! (if (:flipped card)
                                            (assoc card
                                                   :flipped false
                                                   :code (subs (:code card) 0 5))
                                            (assoc card
                                                   :flipped true
                                                   :code (str (subs (:code card) 0 5) "flip")))))}]}

   "Hyoubu Institute: Absolute Clarity"
   {:events [{:event :corp-reveal
              :once :per-turn
              :req (req (first-event? state side :corp-reveal))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits 1))}]
    :abilities [{:cost [:click 1]
                 :label "Reveal the top card of the Stack"
                 :effect (req (when-let [revealed-card (-> runner :deck first)]
                                (system-msg state side (str "uses Hyoubu Institute: Absolute Clarity to reveal " (:title revealed-card)))
                                (reveal state side revealed-card)))}
                {:cost [:click 1]
                 :label "Reveal a random card from the Grip"
                 :effect (req (when-let [revealed-card (-> runner :hand shuffle first)]
                                (system-msg state side (str "uses Hyoubu Institute: Absolute Clarity to reveal " (:title revealed-card)))
                                (reveal state side revealed-card)))}]}

   "Iain Stirling: Retired Spook"
   (let [ability {:req (req (> (:agenda-point corp) (:agenda-point runner)))
                  :once :per-turn
                  :msg "gain 2 [Credits]"
                  :effect (effect (gain-credits 2))}]
     {:flags {:drip-economy true}
      :events [(assoc ability :event :runner-turn-begins)]
      :abilities [ability]})

   "Industrial Genomics: Growing Solutions"
   {:constant-effects [{:type :trash-cost
                        :value (req (count (remove :seen (:discard corp))))}]}

   "Information Dynamics: All You Need To Know"
   {:events (let [inf {:req (req (and (not (:disabled card))
                                      (has-most-faction? state :corp "NBN")))
                       :msg "give the Runner 1 tag"
                       :async true
                       :effect (effect (gain-tags :corp eid 1))}]
              [{:event :pre-start-game
                :effect draft-points-target}
               (assoc inf :event :agenda-scored)
               (assoc inf :event :agenda-stolen)])}

   "Jamie \"Bzzz\" Micken: Techno Savant"
   {:events [{:event :pre-start-game
              :effect draft-points-target}
             {:event :runner-install
              :req (req (and (has-most-faction? state :runner "Shaper")
                             (first-event? state side :runner-install)))
              :msg "draw 1 card"
              :once :per-turn
              :async true
              :effect (effect (draw eid 1 nil))}]}

   "Jemison Astronautics: Sacrifice. Audacity. Success."
   {:events [{:event :corp-forfeit-agenda
              :async true
              :effect (req (show-wait-prompt state :runner "Corp to place advancement tokens")
                           (let [p (inc (get-agenda-points state :corp target))]
                             (continue-ability
                               state side
                               {:prompt "Select a card to place advancement tokens on with Jemison Astronautics: Sacrifice. Audacity. Success."
                                :choices {:card #(and (installed? %)
                                                      (corp? %))}
                                :msg (msg "place " p " advancement tokens on " (card-str state target))
                                :cancel-effect (effect (clear-wait-prompt :runner))
                                :effect (effect (add-prop :corp target :advance-counter p {:placed true})
                                                (clear-wait-prompt :runner))}
                               card nil)))}]}

   "Jesminder Sareen: Girl Behind the Curtain"
   {:events [{:event :pre-tag
              :once :per-run
              :req (req (:run @state))
              :msg "avoid the first tag during this run"
              :effect (effect (tag-prevent :runner 1))}]}

   "Jinteki Biotech: Life Imagined"
   {:events [{:event :pre-first-turn
              :req (req (= side :corp))
              :prompt "Choose a copy of Jinteki Biotech to use this game"
              :choices ["The Brewery" "The Tank" "The Greenhouse"]
              :effect (effect (update! (assoc card :biotech-target target))
                              (system-msg (str "has chosen a copy of Jinteki Biotech for this game")))}]
    :abilities [{:label "Check chosen flip identity"
                 :req (req (:biotech-target card))
                 :effect (req (case (:biotech-target card)
                                "The Brewery"
                                (toast state :corp "Flip to: The Brewery (Do 2 net damage)" "info")
                                "The Tank"
                                (toast state :corp "Flip to: The Tank (Shuffle Archives into R&D)" "info")
                                "The Greenhouse"
                                (toast state :corp "Flip to: The Greenhouse (Place 4 advancement tokens on a card)" "info")))}
                {:cost [:click 3]
                 :req (req (not (:biotech-used card)))
                 :label "Flip this identity"
                 :effect (req (let [flip (:biotech-target card)]
                                (case flip
                                  "The Brewery"
                                  (do (system-msg state side "uses The Brewery to do 2 net damage")
                                      (damage state side eid :net 2 {:card card})
                                      (update! state side (assoc card :code "brewery")))
                                  "The Tank"
                                  (do (system-msg state side "uses The Tank to shuffle Archives into R&D")
                                      (shuffle-into-deck state side :discard)
                                      (update! state side (assoc card :code "tank")))
                                  "The Greenhouse"
                                  (do (system-msg state side (str "uses The Greenhouse to place 4 advancement tokens "
                                                                  "on a card that can be advanced"))
                                      (update! state side (assoc card :code "greenhouse"))
                                      (resolve-ability
                                        state side
                                        {:prompt "Select a card that can be advanced"
                                         :choices {:card can-be-advanced?}
                                         :effect (effect (add-prop target :advance-counter 4 {:placed true}))} card nil)))
                                (update! state side (assoc (get-card state card) :biotech-used true))))}]}

   "Jinteki: Personal Evolution"
   (let [ability {:async true
                  :req (req (not (:winner @state)))
                  :msg "do 1 net damage"
                  :effect (effect (damage eid :net 1 {:card card}))}]
     {:events [(assoc ability
                      :event :agenda-scored
                      :interactive (req true))
               (assoc ability :event :agenda-stolen)]})

   "Jinteki: Potential Unleashed"
   {:events [{:event :pre-resolve-damage
              :req (req (and (-> @state :corp :disable-id not) (= target :net) (pos? (last targets))))
              :effect (req (let [c (first (get-in @state [:runner :deck]))]
                             (system-msg state :corp (str "uses Jinteki: Potential Unleashed to trash " (:title c)
                                                          " from the top of the Runner's Stack"))
                             (mill state :corp :runner 1)))}]}

   "Jinteki: Replicating Perfection"
   {:events [{:event :runner-phase-12
              :effect (req (apply prevent-run-on-server
                                  state card (map first (get-remotes state))))}
             {:event :run
              :once :per-turn
              :req (req (is-central? (:server run)))
              :effect (req (apply enable-run-on-server
                                  state card (map first (get-remotes state))))}]
    :req (req (empty? (let [successes (turn-events state side :successful-run)]
                        (filter #(is-central? %) successes))))
    :effect (req (apply prevent-run-on-server state card (map first (get-remotes state))))
    :leave-play (req (apply enable-run-on-server state card (map first (get-remotes state))))}

   "Kabonesa Wu: Netspace Thrillseeker"
   {:abilities [{:label "Install a non-virus program from your stack, lowering the cost by 1 [Credit]"
                 :cost [:click 1]
                 :prompt "Choose a program"
                 :choices (req (cancellable
                                 (filter #(and (program? %)
                                               (not (has-subtype? % "Virus"))
                                               (can-pay? state :runner eid card nil
                                                         [:credit (install-cost state side % {:cost-bonus -1})]))
                                         (:deck runner))))
                 :msg (msg "install " (:title target) " from the stack, lowering the cost by 1 [Credit]")
                 :async true
                 :effect (effect (trigger-event :searched-stack nil)
                                 (shuffle! :deck)
                                 (register-events
                                   card
                                   [{:event :runner-turn-ends
                                     :duration :end-of-turn
                                     :req (req (some #(get-in % [:special :kabonesa]) (all-installed state :runner)))
                                     :msg (msg "remove " (:title target) " from the game")
                                     :effect (req (doseq [program (filter #(get-in % [:special :kabonesa]) (all-installed state :runner))]
                                                    (move state side program :rfg)))}])
                                 (runner-install (assoc eid :source card :source-type :runner-install)
                                                 (assoc-in target [:special :kabonesa] true)
                                                 {:cost-bonus -1}))}]}

   "Kate \"Mac\" McCaffrey: Digital Tinker"
   ;; Effect marks Kate's ability as "used" if it has already met it's trigger condition this turn
   (letfn [(kate-type? [card] (or (hardware? card)
                                  (program? card)))
           (not-triggered? [state card] (not (get-in @state [:per-turn (:cid card)])))
           (mark-triggered [state card] (swap! state assoc-in [:per-turn (:cid card)] true))]
     {:effect (req (when (pos? (event-count state :runner :runner-install #(kate-type? (first %))))
                     (mark-triggered state card)))
      :constant-effects [{:type :install-cost
                          :req (req (and (kate-type? target)
                                         (not-triggered? state card)))
                          :value -1}]
      :events [{:event :runner-install
                :req (req (and (kate-type? target)
                               (not-triggered? state card)))
                :silent (req true)
                :msg (msg "reduce the install cost of " (:title target) " by 1 [Credits]")
                :effect (req (mark-triggered state card))}]})

   "Ken \"Express\" Tenma: Disappeared Clone"
   {:events [{:event :play-event
              :req (req (and (has-subtype? target "Run")
                             (first-event? state :runner :play-event #(has-subtype? (first %) "Run"))))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits 1))}]}

   "Khan: Savvy Skiptracer"
   {:events [{:event :pass-ice
              :req (req (first-event? state :corp :pass-ice))
              :async true
              :effect (effect
                        (continue-ability
                          (when (some #(and (has-subtype? % "Icebreaker")
                                            (can-pay? state side eid card nil
                                                      [:credit (install-cost state side % {:cost-bonus -1})]))
                                      (:hand runner))
                            {:prompt "Select an icebreaker to install from your Grip"
                             :choices
                             {:req (req (and (in-hand? target)
                                             (has-subtype? target "Icebreaker")
                                             (can-pay? state side eid card nil
                                                       [:credit (install-cost state side target {:cost-bonus -1})])))}
                             :async true
                             :msg (msg "install " (:title target) ", lowering the cost by 1 [Credits]")
                             :effect (effect (runner-install eid target {:cost-bonus -1}))})
                          card nil))}]}

   "Laramy Fisk: Savvy Investor"
   {:events [{:event :successful-run
              :async true
              :interactive (get-autoresolve :auto-fisk (complement never?))
              :silent (get-autoresolve :auto-fisk never?)
              :req (req (and (is-central? (:server run))
                             (first-event? state side :successful-run is-central?)))
              :effect (effect (continue-ability
                                {:optional
                                 {:autoresolve (get-autoresolve :auto-fisk)
                                  :prompt "Force the Corp to draw a card?"
                                  :yes-ability {:msg "force the Corp to draw 1 card"
                                                :async true
                                                :effect (effect (draw :corp eid 1 nil))}
                                  :no-ability {:effect (effect (system-msg "declines to use Laramy Fisk: Savvy Investor"))}}}
                                card nil))}]
    :abilities [(set-autoresolve :auto-fisk "force Corp draw")]}

   "Lat: Ethical Freelancer"
   {:events [{:event :runner-turn-ends
              :req (req (= (count (:hand runner)) (count (:hand corp))))
              :optional {:autoresolve (get-autoresolve :auto-lat)
                         :prompt "Draw 1 card?"
                         :yes-ability {:async true
                                       :msg "draw 1 card"
                                       :effect (effect (draw :runner eid 1 nil))}
                         :no-ability {:effect (effect (system-msg "declines to use Lat: Ethical Freelancer"))}}}]
    :abilities [(set-autoresolve :auto-lat "Lat: Ethical Freelancer")]}

   "Leela Patel: Trained Pragmatist"
   (let [leela {:interactive (req true)
                :prompt "Select an unrezzed card to return to HQ"
                :choices {:card #(and (not (rezzed? %))
                                      (installed? %)
                                      (corp? %))}
                :msg (msg "add " (card-str state target) " to HQ")
                :effect (effect (move :corp target :hand))}]
     {:events [(assoc leela :event :agenda-scored)
               (assoc leela :event :agenda-stolen)]})

   "Liza Talking Thunder: Prominent Legislator"
   {:implementation "Needs to be resolved manually with Crisium Grid"
    :events [{:event :successful-run
              :async true
              :interactive (req true)
              :msg "draw 2 cards and take 1 tag"
              :req (req (and (is-central? (:server run))
                             (first-event? state side :successful-run is-central?)))
              :effect (req (wait-for (gain-tags state :runner 1)
                                     (draw state :runner eid 2 nil)))}]}

   "Los: Data Hijacker"
   {:events [{:event :rez
              :once :per-turn
              :req (req (ice? target))
              :msg "gain 2 [Credits]"
              :effect (effect (gain-credits :runner 2))}]}

   "MaxX: Maximum Punk Rock"
   (let [ability {:msg (msg (let [deck (:deck runner)]
                              (if (pos? (count deck))
                                (str "trash " (join ", " (map :title (take 2 deck))) " from their Stack and draw 1 card")
                                "trash the top 2 cards from their Stack and draw 1 card - but their Stack is empty")))
                  :once :per-turn
                  :async true
                  :effect (effect (mill :runner 2)
                                  (draw eid 1 nil))}]
     {:flags {:runner-turn-draw true
              :runner-phase-12 (req (and (not (:disabled card))
                                         (some #(card-flag? % :runner-turn-draw true) (all-active-installed state :runner))))}
      :events [(assoc ability :event :runner-turn-begins)]
      :abilities [ability]})

   "Mti Mwekundu: Life Improved"
   (let [ability {:once :per-turn
                  :label "Install a piece of ice from HQ at the innermost position"
                  :req (req (and run
                                 (zero? (:position run))
                                 (not (contains? run :corp-phase-43))
                                 (not (contains? run :successful))))
                  :prompt "Choose ICE to install from HQ"
                  :msg "install ice at the innermost position of this server. Runner is now approaching that ice"
                  :choices {:card #(and (ice? %)
                                        (in-hand? %))}
                  :async true
                  :effect (req (corp-install state side eid target (zone->name (first (:server run)))
                                             {:ignore-all-cost true
                                              :front true})
                               (swap! state assoc-in [:run :position] 1))}]
     {:abilities [ability]
      :events [{:event :approach-server
                :req (req (can-trigger? state side ability card nil))
                :effect (req (toast state :corp "You may use Mti Mwekundu: Life Improved to install ice from HQ." "info"))}]})

   "Nasir Meidan: Cyber Explorer"
   {:events [{:event :rez
              :req (req (same-card? target current-ice))
              :msg (msg "lose all credits and gain " (rez-cost state side current-ice)
                        " [Credits] from the rez of " (:title current-ice))
              :effect (effect (lose-credits :runner (:credit runner))
                              (gain-credits :runner (rez-cost state side current-ice)))}]}

   "Nathaniel \"Gnat\" Hall: One-of-a-Kind"
   (let [ability {:label "Gain 1 [Credits] (start of turn)"
                  :once :per-turn
                  :interactive (req true)
                  :effect (req (when (and (> 3 (count (:hand runner)))
                                          (:runner-phase-12 @state))
                                 (system-msg state :runner (str "uses " (:title card) " to gain 1 [Credits]"))
                                 (gain-credits state :runner 1)))}]
     {:flags {:drip-economy true
              :runner-phase-12 (req (and (not (:disabled card))
                                         (some #(card-flag? % :runner-turn-draw true) (all-active-installed state :runner))))}
      :abilities [ability]
      :events [(assoc ability :event :runner-turn-begins)]})

   "NBN: Controlling the Message"
   {:events [{:event :runner-trash
              :async true
              :interactive (req true)
              :req (req (and (= 1 (count (filter #(and (installed? (first %)) (corp? (first %)))
                                                 (turn-events state side :runner-trash))))
                             (corp? target)
                             (installed? target)))
              :effect (req (show-wait-prompt state :runner "Corp to use NBN: Controlling the Message")
                           (continue-ability
                             state :corp
                             {:optional
                              {:prompt "Trace the Runner with NBN: Controlling the Message?"
                               :autoresolve (get-autoresolve :auto-ctm)
                               :yes-ability {:trace {:base 4
                                                     :successful
                                                     {:msg "give the Runner 1 tag"
                                                      :async true
                                                      :effect (effect (gain-tags :corp eid 1 {:unpreventable true}))}}}
                               :end-effect (effect (clear-wait-prompt :runner))}}
                             card nil))}]
    :abilities [(set-autoresolve :auto-ctm "CtM")]}

   "NBN: Making News"
   {:recurring 2
    :interactions {:pay-credits {:req (req (= :trace (:source-type eid)))
                                 :type :recurring}}}

   "NBN: The World is Yours*"
   {:effect (effect (gain :hand-size 1))
    :leave-play (effect (lose :hand-size 1))}

   "Near-Earth Hub: Broadcast Center"
   {:events [{:event :server-created
              :req (req (first-event? state :corp :server-created))
              :msg "draw 1 card"
              :async true
              :effect (effect (draw :corp eid 1 nil))}]}

   "Nero Severn: Information Broker"
   {:abilities [{:req (req (has-subtype? current-ice "Sentry"))
                 :once :per-turn
                 :msg "jack out when encountering a Sentry"
                 :effect (effect (jack-out nil))}]}

   "New Angeles Sol: Your News"
   (let [nasol {:optional
                {:prompt "Play a Current?"
                 :player :corp
                 :req (req (some #(has-subtype? % "Current") (concat (:hand corp) (:discard corp) (:current corp))))
                 :yes-ability {:prompt "Select a Current to play from HQ or Archives"
                               :show-discard true
                               :async true
                               :choices {:card #(and (has-subtype? % "Current")
                                                     (corp? %)
                                                     (#{[:hand] [:discard]} (:zone %)))}
                               :msg (msg "play a current from " (name-zone "Corp" (:zone target)))
                               :effect (effect (play-instant eid target))}}}]
     {:events [(assoc nasol :event :agenda-scored)
               (assoc nasol :event :agenda-stolen)]})

   "NEXT Design: Guarding the Net"
   (let [ndhelper (fn nd [n] {:prompt (str "When finished, click NEXT Design: Guarding the Net to draw back up to 5 cards in HQ. "
                                           "Select a piece of ICE in HQ to install:")
                              :choices {:card #(and (corp? %)
                                                    (ice? %)
                                                    (in-hand? %))}
                              :effect (req (wait-for (corp-install state side target nil nil)
                                                     (continue-ability state side (when (< n 3) (nd (inc n))) card nil)))})]
     {:events [{:event :pre-first-turn
                :req (req (= side :corp))
                :msg "install up to 3 pieces of ICE and draw back up to 5 cards"
                :async true
                :effect (req (wait-for (resolve-ability state side (ndhelper 1) card nil)
                                       (update! state side (assoc card :fill-hq true))
                                       (effect-completed state side eid)))}]
      :abilities [{:req (req (:fill-hq card))
                   :msg (msg "draw " (- 5 (count (:hand corp))) " cards")
                   :effect (req (draw state side (- 5 (count (:hand corp))))
                                (update! state side (dissoc card :fill-hq))
                                (swap! state assoc :turn-events nil))}]})

   "Nisei Division: The Next Generation"
   {:events [{:event :reveal-spent-credits
              :req (req (and (some? (first targets))
                             (some? (second targets))))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits :corp 1))}]}

   "Noise: Hacker Extraordinaire"
   {:events [{:event :runner-install
              :msg "force the Corp to trash the top card of R&D"
              :effect (effect (mill :corp))
              :req (req (has-subtype? target "Virus"))}]}

   "Null: Whistleblower"
   {:abilities [{:once :per-turn
                 :req (req (and run
                                (rezzed? current-ice)
                                (pos? (count (:hand runner)))))
                 :prompt "Select a card in your Grip to trash"
                 :choices {:card in-hand?}
                 :msg (msg "trash " (:title target) " and reduce the strength of " (:title current-ice)
                           " by 2 for the remainder of the run")
                 :effect (effect (update! (assoc-in card [:special :null-target] current-ice))
                                 (update-ice-strength current-ice)
                                 (trash target {:unpreventable true}))}]
    :constant-effects [{:type :ice-strength
                        :req (req (same-card? target (get-in card [:special :null-target])))
                        :value -2}]
    :events [{:event :pass-ice
              :effect (effect (update! (dissoc-in card [:special :null-target]))
                              (update-all-ice))}
             {:event :run-ends
              :effect (effect (update! (dissoc-in card [:special :null-target]))
                              (update-all-ice))}]}

   "Omar Keung: Conspiracy Theorist"
   {:abilities [{:cost [:click 1]
                 :msg "make a run on Archives"
                 :once :per-turn
                 :makes-run true
                 :effect (effect (update! (assoc card :omar-run-activated true))
                                 (make-run :archives nil (get-card state card)))}]
    :events [{:event :pre-successful-run
              :interactive (req true)
              :req (req (and (:omar-run-activated card)
                             (= :archives (-> run :server first))))
              :prompt "Treat as a successful run on which server?"
              :choices ["HQ" "R&D"]
              :effect (req (let [target-server (if (= target "HQ") :hq :rd)]
                             (swap! state update-in [:runner :register :successful-run] #(rest %))
                             (swap! state assoc-in [:run :server] [target-server])
                             (trigger-event state :corp :no-action)
                             (swap! state update-in [:runner :register :successful-run] #(conj % target-server))
                             (system-msg state side (str "uses Omar Keung: Conspiracy Theorist to make a successful run on " target))))}
             {:event :run-ends
              :effect (req (swap! state dissoc-in [:runner :identity :omar-run-activated]))}]}

   "Pālanā Foods: Sustainable Growth"
   {:events [{:event :runner-draw
              :req (req (and (first-event? state :corp :runner-draw)
                             (pos? target)))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits :corp 1))}]}

   "Quetzal: Free Spirit"
   {:abilities [(assoc (break-sub nil 1 "Barrier") :once :per-turn)]}

   "Reina Roja: Freedom Fighter"
   (letfn [(not-triggered? [state card] (not (get-in @state [:per-turn (:cid card)])))
           (mark-triggered [state card] (swap! state assoc-in [:per-turn (:cid card)] true))]
     {:effect (req (when (pos? (event-count state :corp :rez #(ice? (first %))))
                     (mark-triggered state card)))
      :constant-effects [{:type :rez-cost
                          :req (req (and (ice? target)
                                         (not-triggered? state card)))
                          :value 1}]
      :events [{:event :rez
                :req (req (and (ice? target)
                               (not-triggered? state card)))
                :effect (req (mark-triggered state card))}]})

   "Rielle \"Kit\" Peddler: Transhuman"
   {:abilities [{:req (req (and (:run @state)
                                (:rezzed (get-card state current-ice))))
                 :once :per-turn
                 :msg (msg "make " (:title current-ice) " gain Code Gate until the end of the run")
                 :effect (req (let [ice current-ice
                                    stypes (:subtype ice)]
                                (update! state side (assoc ice :subtype (combine-subtypes true stypes "Code Gate")))
                                (register-events
                                  state side card
                                  [{:event :run-ends
                                    :effect (effect (update! (assoc ice :subtype stypes))
                                                    (trigger-event :ice-subtype-changed ice)
                                                    (unregister-events card))}])
                                (update-ice-strength state side ice)
                                (trigger-event state side :ice-subtype-changed ice)))}]
    :events [{:event :run-ends}]}

   "Saraswati Mnemonics: Endless Exploration"
   (letfn [(install-card [chosen]
             {:prompt "Select a remote server"
              :choices (req (conj (vec (get-remote-names state)) "New remote"))
              :async true
              :effect (req (let [tgtcid (:cid chosen)]
                             (register-turn-flag!
                               state side
                               card :can-rez
                               (fn [state side card]
                                 (if (= (:cid card) tgtcid)
                                   ((constantly false) (toast state :corp "Cannot rez due to Saraswati Mnemonics: Endless Exploration." "warning"))
                                   true)))
                             (register-turn-flag!
                               state side
                               card :can-score
                               (fn [state side card]
                                 (if (and (= (:cid card) tgtcid)
                                          (>= (get-counters card :advancement) (or (:current-cost card) (:advancementcost card))))
                                   ((constantly false) (toast state :corp "Cannot score due to Saraswati Mnemonics: Endless Exploration." "warning"))
                                   true))))
                           (wait-for (corp-install state side chosen target nil)
                                     (add-prop state :corp (find-latest state chosen) :advance-counter 1 {:placed true})
                                     (effect-completed state side eid)))})]
     {:abilities [{:async true
                   :label "Install a card from HQ"
                   :cost [:click 1 :credit 1]
                   :prompt "Select a card to install from HQ"
                   :choices {:card #(and (#{"Asset" "Agenda" "Upgrade"} (:type %))
                                      (corp? %)
                                      (in-hand? %))}
                   :msg (msg "install a card in a remote server and place 1 advancement token on it")
                   :effect (effect (continue-ability (install-card target) card nil))}]})

   "Seidr Laboratories: Destiny Defined"
   {:implementation "Manually triggered"
    :abilities [{:req (req (:run @state))
                 :once :per-turn
                 :prompt "Select a card to add to the top of R&D"
                 :show-discard true
                 :choices {:card #(and (corp? %)
                                       (in-discard? %))}
                 :effect (effect (move target :deck {:front true}))
                 :msg (msg "add " (if (:seen target) (:title target) "a card") " to the top of R&D")}]}

   "Silhouette: Stealth Operative"
   {:events [{:event :successful-run
              :interactive (req (some #(not (rezzed? %)) (all-installed state :corp)))
              :async true
              :req (req (and (= target :hq)
                             (first-successful-run-on-server? state :hq)))
              :effect (effect (continue-ability {:choices {:card #(and (installed? %)
                                                                       (not (rezzed? %)))}
                                                 :effect (effect (expose eid target))
                                                 :msg "expose 1 card"
                                                 :async true}
                                                card nil))}]}

   "Skorpios Defense Systems: Persuasive Power"
   {:implementation "Manually triggered, no restriction on which cards in Heap can be targeted. Cannot use on in progress run event"
    :abilities [{:label "Remove a card in the Heap that was just trashed from the game"
                 :async true
                 :effect (req (when-not (and (used-this-turn? (:cid card) state) (active-prompt? state side card))
                                (show-wait-prompt state :runner "Corp to use Skorpios' ability" {:card card})
                                (continue-ability state side
                                                  {:prompt "Choose a card in the Runner's Heap that was just trashed"
                                                   :once :per-turn
                                                   :choices (req (cancellable
                                                                   ;; do not allow a run event in progress to get nuked #2963
                                                                   (remove (fn [c] (some #(same-card? c (:card %)) (:run-effects run)))
                                                                           (:discard runner))))
                                                   :msg (msg "remove " (:title target) " from the game")
                                                   :effect (req (move state :runner target :rfg)
                                                                (clear-wait-prompt state :runner)
                                                                (effect-completed state side eid))
                                                   :cancel-effect (req (clear-wait-prompt state :runner)
                                                                       (effect-completed state side eid))}
                                                  card nil)))}]}

   "Spark Agency: Worldswide Reach"
   {:events [{:event :rez
              :req (req (and (has-subtype? target "Advertisement")
                             (first-event? state :corp :rez #(has-subtype? (first %) "Advertisement"))))
              :effect (effect (lose-credits :runner 1))
              :msg (msg "make the Runner lose 1 [Credits] by rezzing an Advertisement")}]}

   "Sportsmetal: Go Big or Go Home"
   (let [ab {:prompt "Gain 2 credits or draw 2 cards?"
             :player :corp
             :choices ["2 credits" "2 cards"]
             :msg "gain 2 [Credits] or draw 2 cards"
             :async true
             :interactive (req true)
             :effect (req (if (= target "2 credits")
                            (do (system-msg state side "chooses to take 2 [Credits]")
                                (gain-credits state :corp 2)
                                (effect-completed state side eid))
                            (do (system-msg state side "chooses to draw 2 cards")
                                (draw state :corp eid 2 nil))))}]
     {:events [(assoc ab :event :agenda-scored)
               (assoc ab :event :agenda-stolen)]})

   "SSO Industries: Fueling Innovation"
   (letfn [(installed-faceup-agendas [state]
             (->> (all-installed state :corp)
                  (filter agenda?)
                  (filter faceup?)))
           (selectable-ice? [card]
             (and
               (ice? card)
               (installed? card)
               (zero? (+ (get-counters card :advancement)
                         (:extra-advance-counter card 0)))))
           (ice-with-no-advancement-tokens [state]
             (->> (all-installed state :corp)
                  (filter selectable-ice?)))]
     {:events [{:event :corp-turn-ends
                :optional
                {:prompt "Place advancement tokens?"
                 :req (req (and
                             (not-empty (installed-faceup-agendas state))
                             (not-empty (ice-with-no-advancement-tokens state))))
                 :autoresolve (get-autoresolve :auto-sso)
                 :yes-ability
                 {:async true
                  :effect (req (show-wait-prompt state :runner "Corp to use SSO Industries' ability")
                            (let [agendas (installed-faceup-agendas state)
                                  agenda-points (->> agendas
                                                     (map :agendapoints)
                                                     (reduce +))
                                  ice (ice-with-no-advancement-tokens state)]
                              (continue-ability
                                state side
                                {:prompt (str "Select ICE with no advancement tokens to place "
                                              (quantify agenda-points "advancement token") " on")
                                 :choices {:card #(selectable-ice? %)}
                                 :msg (msg "places " (quantify agenda-points "advancement token")
                                           " on ICE with no advancement tokens")
                                 :effect (req (add-prop state :corp target :advance-counter agenda-points {:placed true})
                                              (clear-wait-prompt state :runner))
                                 :cancel-effect (req (clear-wait-prompt state :runner))}
                                card nil)))}}}]
      :abilities [(set-autoresolve :auto-sso "SSO")]})

   "Steve Cambridge: Master Grifter"
   {:events [{:event :successful-run
              :req (req (and (= target :hq)
                             (first-successful-run-on-server? state :hq)
                             (<= 2 (count (:discard runner)))))
              :interactive (req true)
              :async true
              :prompt "Select 2 cards in your Heap"
              :show-discard true
              :choices {:max 2
                        :card #(and (in-discard? %)
                                    (runner? %))}
              :effect (req (let [c1 (first targets)
                                 c2 (second targets)]
                             (show-wait-prompt state :runner "Corp to choose which card to remove from the game")
                             (continue-ability
                               state :corp
                               {:prompt "Choose which card to remove from the game"
                                :player :corp
                                :choices [c1 c2]
                                :effect (req (let [[chosen other] (if (= target c1)
                                                                    [c1 c2]
                                                                    [c2 c1])]
                                               (move state :runner chosen :rfg)
                                               (move state :runner other :hand)
                                               (system-msg state :runner
                                                           (str "uses Steve Cambridge: Master Grifter"
                                                                " to add " (:title other) " to their grip."
                                                                " Corp removes " (:title chosen) " from the game")))
                                             (clear-wait-prompt state :runner))}
                               card nil)))}]}

   "Strategic Innovations: Future Forward"
   {:events [{:event :pre-start-game
              :effect draft-points-target}
             {:event :runner-turn-ends
              :req (req (and (not (:disabled card))
                             (has-most-faction? state :corp "Haas-Bioroid")
                             (pos? (count (:discard corp)))))
              :prompt "Select a card in Archives to shuffle into R&D"
              :choices {:card #(and (corp? %)
                                    (in-discard? %))}
              :player :corp
              :show-discard true
              :priority true
              :msg (msg "shuffle " (if (:seen target) (:title target) "a card")
                        " into R&D")
              :effect (effect (move :corp target :deck)
                              (shuffle! :corp :deck))}]}

   "Sunny Lebeau: Security Specialist"
   ;; No special implementation
   {}

   "SYNC: Everything, Everywhere"
   {:effect (req (when (> (:turn @state) 1)
                   (if (:sync-front card)
                     (tag-remove-bonus state side -1)
                     (trash-resource-bonus state side 2))))
    :events [{:event :pre-first-turn
              :req (req (= side :corp))
              :effect (effect (update! (assoc card :sync-front true))
                              (tag-remove-bonus -1))}]
    :abilities [{:cost [:click 1]
                 :effect (req (if (:sync-front card)
                                (do (tag-remove-bonus state side 1)
                                    (trash-resource-bonus state side 2)
                                    (update! state side (-> card (assoc :sync-front false
                                                                        :code "sync"))))
                                (do (tag-remove-bonus state side -1)
                                    (trash-resource-bonus state side -2)
                                    (update! state side (-> card (assoc :sync-front true
                                                                        :code "09001"))))))
                 :label "Flip this identity"
                 :msg (msg "flip their ID")}]
    :leave-play (req (if (:sync-front card)
                       (tag-remove-bonus state side 1)
                       (trash-resource-bonus state side -2)))}

   "Synthetic Systems: The World Re-imagined"
   {:events [{:event :pre-start-game
              :effect draft-points-target}]
    :flags {:corp-phase-12 (req (and (not (:disabled (get-card state card)))
                                     (has-most-faction? state :corp "Jinteki")
                                     (> (count (filter ice? (all-installed state :corp))) 1)))}
    :abilities [{:prompt "Select two pieces of ICE to swap positions"
                 :choices {:card #(and (installed? %)
                                       (ice? %))
                           :max 2}
                 :once :per-turn
                 :effect (req (when (= (count targets) 2)
                                (swap-ice state side (first targets) (second targets))))
                 :msg (msg "swap the positions of " (card-str state (first targets))
                           " and " (card-str state (second targets)))}]}

   "Tennin Institute: The Secrets Within"
   {:flags {:corp-phase-12 (req (and (not (:disabled (get-card state card)))
                                     (not-last-turn? state :runner :successful-run)))}
    :abilities [{:msg (msg "place 1 advancement token on " (card-str state target))
                 :label "Place 1 advancement token on a card if the Runner did not make a successful run last turn"
                 :choices {:card installed?}
                 :req (req (and (:corp-phase-12 @state) (not-last-turn? state :runner :successful-run)))
                 :once :per-turn
                 :effect (effect (add-prop target :advance-counter 1 {:placed true}))}]}

   "The Foundry: Refining the Process"
   {:events [{:event :rez
              :req (req (and (ice? target) ;; Did you rez and ice just now
                             (first-event? state :runner :rez #(ice? (first %)))))
              :optional
              {:prompt "Add another copy to HQ?"
               :yes-ability
               {:effect (req (if-let [found-card (some #(when (= (:title %) (:title target)) %) (concat (:deck corp) (:play-area corp)))]
                               (do (move state side found-card :hand)
                                   (system-msg state side (str "uses The Foundry to add a copy of "
                                                               (:title found-card) " to HQ, and shuffles their deck"))
                                   (shuffle! state side :deck))
                               (do (system-msg state side (str "fails to find a target for The Foundry, and shuffles their deck"))
                                   (shuffle! state side :deck))))}}}]}

   "The Masque: Cyber General"
   {:events [{:event :pre-start-game
              :effect draft-points-target}]}

   "The Outfit: Family Owned and Operated"
   {:events [{:event :corp-gain-bad-publicity
              :msg "gain 3 [Credit]"
              :effect (effect (gain-credits 3))}]}

   "The Professor: Keeper of Knowledge"
   ;; No special implementation
   {}

   "The Shadow: Pulling the Strings"
   {:events [{:event :pre-start-game
              :effect draft-points-target}]}

   "Titan Transnational: Investing In Your Future"
   {:events [{:event :agenda-scored
              :msg (msg "add 1 agenda counter to " (:title target))
              :effect (effect (add-counter (get-card state target) :agenda 1))}]}

   "Valencia Estevez: The Angel of Cayambe"
   {:events [{:event :pre-start-game
              :req (req (and (= side :runner)
                             (zero? (count-bad-pub state))))
              ;; This doesn't use `gain-bad-publicity` to avoid the event
              :effect (effect (gain :corp :bad-publicity 1))}]}

   "Weyland Consortium: Because We Built It"
   {:recurring 1
    :interactions {:pay-credits {:req (req (= :advance (:source-type eid)))
                                 :type :recurring}}}

   "Weyland Consortium: Builder of Nations"
   {:implementation "Encounter effect is manual"
    :abilities [{:async true
                 :label "Do 1 meat damage"
                 :once :per-turn
                 :msg "do 1 meat damage"
                 :effect (effect (damage eid :meat 1 {:card card}))}]}

   "Weyland Consortium: Building a Better World"
   {:events [{:event :play-operation
              :req (req (has-subtype? target "Transaction"))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits 1))}]}

   "Whizzard: Master Gamer"
   {:recurring 3
    :interactions {:pay-credits {:req (req (and (= :runner-trash-corp-cards (:source-type eid))
                                                (corp? target)))
                                 :type :recurring}}}

   "Wyvern: Chemically Enhanced"
   {:events [{:event :pre-start-game
              :effect draft-points-target}
             {:event :runner-trash
              :interactive (req true)
              :req (req (and (has-most-faction? state :runner "Anarch")
                             (some corp? targets)
                             (pos? (count (:discard runner)))))
              :msg (msg "shuffle " (:title (last (:discard runner))) " into their Stack")
              :effect (effect (move :runner (last (:discard runner)) :deck)
                              (shuffle! :runner :deck)
                              (trigger-event :searched-stack nil))}]}})
