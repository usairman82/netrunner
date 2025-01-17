(ns game.cards.hardware
  (:require [game.core :refer :all]
            [game.core.card :refer :all]
            [game.core.effects :refer [register-floating-effect]]
            [game.core.eid :refer [make-eid make-result effect-completed]]
            [game.core.card-defs :refer [card-def]]
            [game.core.prompts :refer [show-wait-prompt clear-wait-prompt]]
            [game.core.toasts :refer [toast]]
            [game.utils :refer :all]
            [game.macros :refer [effect req msg wait-for continue-ability]]
            [clojure.string :refer [split-lines split join lower-case includes? starts-with?]]
            [clojure.stacktrace :refer [print-stack-trace]]
            [jinteki.utils :refer :all]))

;; Card definitions
(def card-definitions
  {"Acacia"
   {:events [{:event :pre-purge
              :effect (req (let [counters (number-of-virus-counters state)]
                             (update! state side (assoc-in (get-card state card) [:special :numpurged] counters))))}
             {:event :purge
              :async true
              :effect (effect (show-wait-prompt  :corp "Runner to decide if they will use Acacia")
                              (continue-ability
                                {:optional
                                 {:player :runner
                                  :prompt "Use Acacia?"
                                  :yes-ability {:effect (req (let [counters (- (get-in (get-card state card) [:special :numpurged])
                                                                               (number-of-virus-counters state))]
                                                               (wait-for (trash state side card nil)
                                                                         (gain-credits state side counters)
                                                                         (system-msg state side (str "uses Acacia and gains " counters "[Credit]"))
                                                                         (clear-wait-prompt state :corp)
                                                                         (effect-completed state side eid))))}
                                  :no-ability {:effect (effect (clear-wait-prompt :corp)
                                                               (effect-completed eid))}}}
                                card nil))}]}

   "Adjusted Matrix"
   {:implementation "Click Adjusted Matrix to use ability."
    :req (req (not-empty (filter #(has-subtype? % "Icebreaker") (all-active-installed state :runner))))
    :prompt "Choose Icebreaker on which to install Adjusted Matrix"
    :choices {:card #(and (runner? %)
                          (has-subtype? % "Icebreaker")
                          (installed? %))}
    :msg (msg "host it on " (card-str state target))
    :effect (effect (update! (assoc target :subtype (combine-subtypes false (-> target :subtype) "AI")))
                    (host (get-card state target) (get-card state card)))
    :abilities [(break-sub [:click 1] 1 "All" {:req (req true)})]
    :events [{:event :pre-card-moved
              :req (req (same-card? target card))
              :effect (effect (update! (assoc (-> card :host) :subtype (-> card :host :subtype (remove-subtypes-once ["AI"])))))}]}

   "Akamatsu Mem Chip"
   {:in-play [:memory 1]}

   "Archives Interface"
   {:events
    [{:event :pre-access
      :async true
      :interactive (req true)
      :req (req (and (= target :archives)
                     (not= (:max-access run) 0)
                     (not-empty (:discard corp))))
      :effect (req (swap! state update-in [:corp :discard] #(map (fn [c] (assoc c :seen true)) %))
                   (continue-ability
                     state side
                     {:optional
                      {:prompt "Use Archives Interface to remove a card from the game instead of accessing it?"
                       :yes-ability {:prompt "Choose a card in Archives to remove from the game instead of accessing"
                                     :choices (req (:discard corp))
                                     :msg (msg "remove " (:title target) " from the game")
                                     :effect (effect (move :corp target :rfg))}}} card nil))}]}

   "Astrolabe"
   {:in-play [:memory 1]
    :events [{:event :server-created
              :msg "draw 1 card"
              :async true
              :effect (effect (draw :runner eid 1 nil))}]}

   "Autoscripter"
   {:events [{:event :runner-install
              :silent (req true)
              :req (req (and (program? target)
                             ;; only trigger on Runner's turn
                             (= (:active-player @state) :runner)
                             ;; only trigger when playing a Program from grip
                             (some #{:hand} (:previous-zone target))
                             ;; check that we haven't played a Program from the grip this turn
                             ;; which translates to just one case of playing a Program in turn-events
                             (first-event? state :runner :runner-install
                                           (fn [[card _]] (and (some #{:hand} (:previous-zone card))
                                                               (program? card))))))
              :msg "gain [Click]"
              :effect (effect (gain :click 1))}
             {:event :unsuccessful-run
              :effect (effect (trash card)
                              (system-msg "trashes Autoscripter"))}]}

   "Blackguard"
   {:in-play [:memory 2]
    :events [{:event :expose
              :msg (msg "attempt to force the rez of " (:title target))
              :async true
              :effect (req (let [c target
                                 cdef (card-def c)
                                 cname (:title c)
                                 cost (rez-cost state side target)
                                 additional-costs (rez-additional-cost-bonus state side target)]
                             (if (seq additional-costs)
                               (do (show-wait-prompt state :runner (str "Corp to decide if they will rez " cname))
                                   (continue-ability
                                     state side
                                     {:optional
                                      {:prompt (msg (build-cost-string [:credit cost])
                                                    ", plus " (lower-case (build-cost-string additional-costs))
                                                    " as an additional cost to rez " cname "?")
                                       :player :corp
                                       :yes-ability {:effect (effect (rez :corp c))}
                                       :no-ability {:msg (msg "declines to pay additional costs"
                                                              " and is not forced to rez " cname)}
                                       :end-effect (effect (clear-wait-prompt :runner))}}
                                     card nil))
                               (do (rez state :corp target)
                                   (effect-completed state side eid)))))}]}

   "Bookmark"
   {:abilities [{:label "Host up to 3 cards from your Grip facedown"
                 :cost [:click 1]
                 :msg "host up to 3 cards from their Grip facedown"
                 :choices {:max 3
                           :card #(and (runner? %)
                                       (in-hand? %))}
                 :effect (req (doseq [c targets]
                                (host state side (get-card state card) c {:facedown true})))}
                {:label "Add all hosted cards to Grip"
                 :cost [:click 1]
                 :msg "add all hosted cards to their Grip"
                 :effect (req (doseq [c (:hosted card)]
                                (move state side c :hand)))}
                {:label "Add all hosted cards to Grip"
                 :effect (req (doseq [c (:hosted card)]
                                (move state side c :hand))
                              (continue-ability
                                state side
                                {:cost [:trash]
                                 :msg "add all hosted cards to their Grip"}
                                (get-card state card) nil))}]}

   "Box-E"
   {:in-play [:memory 2 :hand-size 2]}

   "Brain Cage"
   {:in-play [:hand-size 3]
    :effect (effect (damage eid :brain 1 {:card card}))}

   "Brain Chip"
   (let [runner-points (fn [s] (max (get-in s [:runner :agenda-point] 0) 0))]
     {:effect (req (gain state :runner
                         :memory (runner-points @state)
                         :hand-size (runner-points @state))
                   (add-watch state (keyword (str "brainchip" (:cid card)))
                              (fn [k ref old new]
                                (let [bonus (- (runner-points new) (runner-points old))]
                                  (when-not (zero? bonus)
                                    (gain state :runner
                                          :memory bonus
                                          :hand-size bonus))))))
      :leave-play (req (remove-watch state (keyword (str "brainchip" (:cid card))))
                       (lose state :runner
                             :memory (runner-points @state)
                             :hand-size (runner-points @state)))})

   "Capstone"
   {:abilities [{:req (req (pos? (count (:hand runner))))
                 :cost [:click 1]
                 :effect (req (let [handsize (count (:hand runner))]
                                (resolve-ability
                                  state side
                                  {:prompt "Select any number of cards to trash from your Grip"
                                   :choices {:max handsize
                                             :card #(and (runner? %)
                                                         (in-hand? %))}
                                   :effect (req (let [trashed (count targets)
                                                      remaining (- handsize trashed)]
                                                  (doseq [c targets]
                                                    (when (not (empty? (filter #(= (:title c) (:title %))
                                                                               (all-active-installed state :runner))))
                                                      (draw state side)))
                                                  (trash-cards state side targets)
                                                  (system-msg state side
                                                              (str "spends [Click] to use Capstone to trash "
                                                                   (join ", " (map :title targets)) " and draw "
                                                                   (- (count (get-in @state [:runner :hand])) remaining) " cards"))))}
                                  card nil)))}]}

   "Chop Bot 3000"
   {:flags {:runner-phase-12 (req (>= 2 (count (all-installed state :runner))))}
    :abilities [{:req (req (:runner-phase-12 @state))
                 :msg (msg "trash " (:title target))
                 :choices {:card #(and (runner? %)
                                       (installed? %))
                           :not-self true}
                 :async true
                 :effect (req (wait-for (trash state :runner target nil)
                                        (continue-ability
                                          state side
                                          (let [deck (pos? (count (:deck runner)))
                                                tags (pos? (count-tags state))]
                                            {:req (req (or deck tags))
                                             :prompt "Draw 1 card or remove 1 tag"
                                             :choices (concat (when deck ["Draw 1 card"])
                                                              (when tags ["Remove 1 tag"]))
                                             :async true
                                             :effect (req (if (= target "Draw 1 card")
                                                            (draw state side eid 1 nil)
                                                            (do (lose-tags state :runner 1)
                                                                (effect-completed state side eid))))})
                                          card nil)))}]}

   "Clone Chip"
   {:abilities [{:prompt "Select a program to install from your Heap"
                 :priority true
                 :show-discard true
                 :req (req (and (not (seq (get-in @state [:runner :locked :discard])))
                                (not (install-locked? state side))
                                (some #(and (program? %)
                                            (can-pay? state side eid card nil
                                                      [:credit (install-cost state side %)]))
                                      (:discard runner))))
                 :choices {:req (req (and (program? target)
                                          (in-discard? target)
                                          (can-pay? state side eid card nil
                                                    [:credit (install-cost state side target)])))}
                 :cost [:trash]
                 :msg (msg "install " (:title target))
                 :effect (effect (runner-install eid target nil))}]}

   "Comet"
   {:in-play [:memory 1]
    :events [{:event :play-event
              :req (req (first-event? state side :play-event))
              :effect (req (system-msg state :runner
                                       (str "can play another event without spending a [Click] by clicking on Comet"))
                           (update! state side (assoc card :comet-event true)))}]
    :abilities [{:req (req (:comet-event card))
                 :prompt "Select an Event in your Grip to play"
                 :choices {:card #(and (event? %)
                                       (in-hand? %))}
                 :msg (msg "play " (:title target))
                 :effect (effect (play-instant target)
                                 (update! (dissoc (get-card state card) :comet-event)))}]}

   "Cortez Chip"
   {:abilities [{:prompt "Select a piece of ICE"
                 :choices {:card #(and (ice? %)
                                       (not (rezzed? %)))}
                 :msg (msg "increase the rez cost of " (card-str state target)
                           " by 2 [Credits] until the end of the turn")
                 :cost [:trash]
                 :effect (effect (register-floating-effect
                                   card
                                   (let [ice target]
                                     {:type :rez-additional-cost
                                      :duration :end-of-turn
                                      :req (req (same-card? target ice))
                                      :value [:credit 2]})))}]}

   "Cyberdelia"
   {:in-play [:memory 1]
    :events [{:once :per-turn
              :msg "gain 1 [Credits] for breaking all subroutines on a piece of ice"
              :effect (effect (gain-credits 1))
              :event :pass-ice
              :req (req (and (rezzed? current-ice)
                             (empty? (remove :broken (:subroutines current-ice)))))}]}

   "Cyberfeeder"
   {:recurring 1
    :interactions {:pay-credits {:req (req (or (and (= :runner-install (:source-type eid))
                                                    (has-subtype? target "Virus")
                                                    (program? target))
                                               (and (= :ability (:source-type eid))
                                                    (has-subtype? target "Icebreaker"))))
                                 :type :recurring}}}

   "CyberSolutions Mem Chip"
   {:in-play [:memory 2]}

   "Cybsoft MacroDrive"
   {:recurring 1
    :interactions {:pay-credits {:req (req (and (= :runner-install (:source-type eid))
                                                (program? target)))
                                 :type :recurring}}}

   "Daredevil"
   {:in-play [:memory 2]
    :events [{:event :run-big
              :once :per-turn
              :req (req (first-event? state side :run-big))
              :msg "draw two cards"
              :async true
              :effect (effect (draw eid 2 nil))}]}

   "Dedicated Processor"
   {:implementation "Click Dedicated Processor to use ability"
    :req (req (not-empty (filter #(has-subtype? % "Icebreaker") (all-active-installed state :runner))))
    :hosting {:card #(and (has-subtype? % "Icebreaker")
                          (not (has-subtype? % "AI"))
                          (installed? %))}
    :abilities [{:cost [:credit 2]
                 :req (req run)
                 :effect (effect (pump (get-card state (:host card)) 4))
                 :msg (msg (str "pump the strength of " (get-in card [:host :title]) " by 4"))}]}

   "Deep Red"
   {:implementation "MU use restriction not enforced"
    :in-play [:memory 3]
    :events [{:event :runner-install
              :optional
              {:req (req (has-subtype? target "Caïssa"))
               :prompt "Use Deep Red?" :priority 1
               :yes-ability {:async true
                             :effect (req (let [cid (:cid target)]
                                            (continue-ability
                                              state side
                                              {:async true
                                               :prompt "Choose the just-installed Caïssa to have Deep Red trigger its [Click] ability"
                                               :choices {:card #(= cid (:cid %))}
                                               :msg (msg "trigger the [Click] ability of " (:title target)
                                                         " without spending [Click]")
                                               :effect (req (gain state :runner :click 1)
                                                            (play-ability state side {:card target :ability 0})
                                                            (effect-completed state side eid))}
                                              card nil)))}}}]}

   "Demolisher"
   {:in-play [:memory 1]
    :constant-effects [{:type :trash-cost
                        :value -1}]
    :events [{:event :runner-trash
              :once :per-turn
              :req (req (some corp? targets))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits 1))}]}

   "Desperado"
   {:in-play [:memory 1]
    :events [{:event :successful-run
              :silent (req true)
              :msg "gain 1 [Credits]" :effect (effect (gain-credits 1))}]}

   "Dinosaurus"
   {:abilities [{:label "Install a non-AI icebreaker on Dinosaurus"
                 :req (req (empty? (:hosted card))) :cost [:click 1]
                 :prompt "Select a non-AI icebreaker in your Grip to install on Dinosaurus"
                 :choices {:card #(and (has-subtype? % "Icebreaker")
                                       (not (has-subtype? % "AI"))
                                       (in-hand? %))}
                 :async true
                 :effect (effect (update! (assoc-in (get-card state card) [:special :dino-breaker] (:cid target)))
                                 (runner-install eid target {:host-card card :no-mu true}))}
                {:label "Host an installed non-AI icebreaker on Dinosaurus"
                 :req (req (empty? (:hosted card)))
                 :prompt "Select an installed non-AI icebreaker to host on Dinosaurus"
                 :choices {:card #(and (has-subtype? % "Icebreaker")
                                       (not (has-subtype? % "AI"))
                                       (installed? %))}
                 :msg (msg "host " (:title target))
                 :effect (req (free-mu state (:memoryunits target))
                              (->> target
                                   (get-card state)
                                   (host state side card)
                                   (update-breaker-strength state side))
                              (update! state side (assoc-in (get-card state card) [:special :dino-breaker] (:cid target))))}]
    :constant-effects [{:type :breaker-strength
                        :req (req (same-card? target (first (:hosted card))))
                        :value 2}]
    :events [{:event :card-moved
              :req (req (= (:cid target) (get-in (get-card state card) [:special :dino-breaker])))
              :effect (effect (update! (dissoc-in card [:special :dino-breaker]))
                              (use-mu (:memoryunits target)))}]}

   "Doppelgänger"
   {:in-play [:memory 1]
    :events [{:event :runner-install
              :req (req (= card target))
              :silent (req true)
              :effect (effect (update! (assoc card :dopp-active true)))}
             {:event :runner-turn-begins
              :effect (effect (update! (assoc card :dopp-active true)))}
             {:event :successful-run-ends
              :interactive (req true)
              :optional
              {:req (req (:dopp-active card))
               :player :runner
               :prompt "Use Doppelgänger to run again?"
               :yes-ability {:prompt "Choose a server"
                             :async true
                             :choices (req runnable-servers)
                             :msg (msg "make a run on " target)
                             :makes-run true
                             :effect (effect (update! (dissoc card :dopp-active))
                                             (clear-wait-prompt :corp)
                                             (make-run eid target))}}}]}

   "Dorm Computer"
   {:data {:counter {:power 4}}
    :abilities [{:cost [:click 1 :power 1]
                 :req (req (not run))
                 :prompt "Choose a server"
                 :choices (req runnable-servers)
                 :msg "make a run and avoid all tags for the remainder of the run"
                 :makes-run true
                 :effect (effect (update! (assoc card :dorm-active true))
                                 (make-run target))}]
    :events [{:event :pre-tag
              :req (req (:dorm-active card))
              :effect (effect (tag-prevent :runner Integer/MAX_VALUE))
              :msg "avoid all tags during the run"}
             {:event :run-ends
              :effect (effect (update! (dissoc card :dorm-active)))}]}

   "Dyson Fractal Generator"
   {:recurring 1
    :interactions {:pay-credits {:req (req (and (= :ability (:source-type eid))
                                                (has-subtype? target "Fracter")
                                                (has-subtype? target "Icebreaker")))
                                 :type :recurring}}}

   "Dyson Mem Chip"
   {:in-play [:memory 1 :link 1]}

   "e3 Feedback Implants"
   {:abilities [(break-sub 1 1 "All" {:req (req true)})]}

   "Ekomind"
   (let [update-base-mu (fn [state n] (swap! state assoc-in [:runner :memory :base] n))]
     {:effect (req (update-base-mu state (count (get-in @state [:runner :hand])))
                   (add-watch state :ekomind (fn [k ref old new]
                                               (let [hand-size (count (get-in new [:runner :hand]))]
                                                 (when (not= (count (get-in old [:runner :hand])) hand-size)
                                                   (update-base-mu ref hand-size))))))
      :leave-play (req (remove-watch state :ekomind))})

   "EMP Device"
   {:abilities [{:req (req (:run @state))
                 :msg "prevent the Corp from rezzing more than 1 piece of ICE for the remainder of the run"
                 :cost [:trash]
                 :effect (effect (register-events
                                   (assoc card :zone '(:discard))
                                   [{:event :rez
                                     :req (req (ice? target))
                                     :effect (effect (register-run-flag!
                                                       card :can-rez
                                                       (fn [state side card]
                                                         (if (ice? card)
                                                           ((constantly false)
                                                            (toast state :corp "Cannot rez ICE the rest of this run due to EMP Device"))
                                                           true))))}
                                    {:event :run-ends
                                     :effect (effect (unregister-events card))}]))}]
    :events [{:event :rez}
             {:event :run-ends}]}

   "Feedback Filter"
   {:interactions {:prevent [{:type #{:net :brain}
                              :req (req true)}]}
    :abilities [{:cost [:credit 3]
                 :msg "prevent 1 net damage"
                 :effect (effect (damage-prevent :net 1))}
                {:label "Prevent up to 2 brain damage"
                 :msg "prevent up to 2 brain damage"
                 :cost [:trash]
                 :effect (effect (damage-prevent :brain 2))}]}

   "Flame-out"
   (let [turn-end {:async true
                   :effect (req (unregister-events state :runner card)
                                (if-let [hosted (first (:hosted card))]
                                  (do
                                    (system-msg state :runner (str "trashes " (:title hosted) " from Flame-out"))
                                    (trash state side eid hosted nil))
                                  (effect-completed state side eid)))}]
     {:implementation "Credit usage restriction not enforced"
      :data {:counter {:credit 9}}
      :abilities [{:label "Take 1 [Credits] from Flame-out"
                   :req (req (and (not-empty (:hosted card))
                                  (pos? (get-counters card :credit))))
                   :effect (req (add-counter state side card :credit -1)
                                (gain-credits state :runner 1)
                                (system-msg state :runner "takes 1 [Credits] from Flame-out")
                                (register-events
                                  state :runner (get-card state card)
                                  [(assoc turn-end :event :runner-turn-ends)
                                   (assoc turn-end :event :corp-turn-ends)]))}
                  {:label "Take all [Credits] from Flame-out"
                   :req (req (and (not-empty (:hosted card))
                                  (pos? (get-counters card :credit))))
                   :effect (req (let [credits (get-counters card :credit)]
                                  (gain-credits state :runner credits)
                                  (update! state :runner (dissoc-in card [:counter :credit]))
                                  (system-msg state :runner (str "takes " credits "[Credits] from Flame-out"))
                                  (register-events
                                    state :runner (get-card state card)
                                    [(assoc turn-end :event :runner-turn-ends)
                                     (assoc turn-end :event :corp-turn-ends)])))}
                  {:label "Install a program on Flame-out"
                   :req (req (empty? (:hosted card)))
                   :cost [:click 1]
                   :prompt "Select a program in your Grip to install on Flame-out"
                   :choices {:card #(and (program? %)
                                         (in-hand? %))}
                   :async true
                   :effect (effect (update! (assoc-in (get-card state card) [:special :flame-out] (:cid target)))
                                   (runner-install eid target {:host-card card}))}
                  {:label "Host an installed program on Flame-out"
                   :req (req (empty? (:hosted card)))
                   :prompt "Select an installed program to host on Flame-out"
                   :choices {:card #(and (program? %)
                                         (installed? %))}
                   :msg (msg "host " (:title target))
                   :effect (req (->> target
                                     (get-card state)
                                     (host state side card))
                                (update! state side (assoc-in (get-card state card) [:special :flame-out] (:cid target))))}]
      :events [{:event :card-moved
                :req (req (= (:cid target) (get-in (get-card state card) [:special :flame-out])))
                :effect (effect (update! (dissoc-in card [:special :flame-out])))}
               {:event :runner-turn-ends}
               {:event :corp-turn-ends}]
      :interactions {:pay-credits {:req (req (and (= :ability (:source-type eid))
                                                  (same-card? card (:host target))
                                                  (pos? (get-counters card :credit))))
                                   :custom-amount 1
                                   :custom (req (add-counter state side card :credit -1)
                                                (register-events
                                                  state side (get-card state card)
                                                  [(assoc turn-end :event :runner-turn-ends)
                                                   (assoc turn-end :event :corp-turn-ends)])
                                                (effect-completed state side (make-result eid 1)))
                                   :type :custom}}})

   "Flip Switch"
   {:events [{:event :pre-init-trace
              :async true
              :req (req (= :runner (:active-player @state)))
              :effect (effect (show-wait-prompt :corp "Runner to use Flip Switch")
                              (continue-ability
                                :runner
                                {:optional
                                 {:prompt "Use Flip Switch to reduce base trace strength to 0?"
                                  :yes-ability {:msg "reduce the base trace strength to 0"
                                                :cost [:trash]
                                                :effect (req (swap! state assoc-in [:trace :force-base] 0)
                                                             (effect-completed state side eid))}
                                  :end-effect (effect (clear-wait-prompt :corp))}}
                                card nil))}]
    :abilities [{:label "Jack out"
                 :req (req (and run
                                (= :runner (:active-player @state))))
                 :msg "jack out"
                 :cost [:trash]
                 :effect (effect (jack-out eid))}
                {:label "Remove 1 tag"
                 :req (req (and (pos? (count-tags state))
                                (= :runner (:active-player @state))))
                 :msg "remove 1 tag"
                 :cost [:trash]
                 :effect (effect (lose-tags eid 1))}]}

   "Forger"
   {:interactions {:prevent [{:type #{:tag}
                              :req (req true)}]}
    :in-play [:link 1]
    :abilities [{:msg "avoid 1 tag"
                 :label "Avoid 1 tag"
                 :cost [:trash]
                 :effect (effect (tag-prevent :runner 1))}
                {:msg "remove 1 tag"
                 :label "Remove 1 tag"
                 :cost [:trash]
                 :effect (effect (lose-tags 1))}]}

   "Friday Chip"
   (let [ability {:msg (msg "move 1 virus counter to " (:title target))
                  :req (req (and (pos? (get-counters card :virus))
                                 (pos? (count-virus-programs state))))
                  :choices {:card virus-program?}
                  :effect (req (add-counter state :runner card :virus -1)
                               (add-counter state :runner target :virus 1))}]
     {:abilities [(set-autoresolve :auto-accept "Friday Chip")]
      :effect (effect (toast "Tip: You can toggle automatically adding virus counters by clicking Friday Chip."))
      :events [(assoc ability :event :runner-turn-begins)
               {:event :runner-trash
                :async true
                :req (req (some corp? targets))
                :effect (req (let [amt-trashed (count (filter corp? targets))
                                   sing-ab {:optional
                                            {:prompt "Place a virus counter on Friday Chip?"
                                             :autoresolve (get-autoresolve :auto-accept)
                                             :yes-ability {:effect (effect (system-msg
                                                                             :runner
                                                                             "places 1 virus counter on Friday Chip")
                                                                           (add-counter :runner card :virus 1))}}}
                                   mult-ab {:prompt "Place virus counters on Friday Chip?"
                                            :choices {:number (req amt-trashed)
                                                      :default (req amt-trashed)}
                                            :effect (effect (system-msg :runner
                                                                        (str "places "
                                                                             (quantify target "virus counter")
                                                                             " on Friday Chip"))
                                                            (add-counter :runner card :virus target))}
                                   ab (if (> amt-trashed 1) mult-ab sing-ab)]
                               (continue-ability state side ab card targets)))}]})

   "Gebrselassie"
   {:abilities [{:msg (msg "host it on an installed non-AI icebreaker")
                 :cost [:click 1]
                 :choices {:card #(and (installed? %)
                                       (has-subtype? % "Icebreaker")
                                       (not (has-subtype? % "AI")))}
                 :effect (req (when-let [host (get-card state (:host card))]
                                (swap! state assoc :effects
                                       (reduce
                                         (fn [effects e]
                                           (conj effects
                                                 (if (and (same-card? host (:card e))
                                                          (= :breaker-strength (:type e))
                                                          (:original-duration e))
                                                   (-> e
                                                       (assoc :duration (:original-duration e))
                                                       (dissoc :original-duration))
                                                   e)))
                                         []
                                         (:effects @state)))
                                (update-breaker-strength state side host))
                              (host state side target card))}]
    :events [{:event :pump-breaker
              :req (req (same-card? target (:host card)))
              :effect (req (let [last-pump (assoc (last (:effects @state))
                                                  :duration :end-of-run
                                                  :original-duration (:duration (last (:effects @state))))]
                             (swap! state assoc :effects
                                    (->> (:effects @state)
                                         butlast
                                         (#(conj % last-pump))
                                         (into []))))
                           (update-breaker-strength state side target))}]
    :leave-play (req (when-let [host (get-card state (:host card))]
                       (swap! state assoc :effects
                              (reduce
                                (fn [effects e]
                                  (conj effects
                                        (if (and (same-card? host (:card e))
                                                 (= :breaker-strength (:type e))
                                                 (:original-duration e))
                                          (-> e
                                              (assoc :duration (:original-duration e))
                                              (dissoc :original-duration))
                                          e)))
                                []
                                (:effects @state)))
                       (update-breaker-strength state side host)))}

   "GPI Net Tap"
   {:implementation "Trash and jack out effect is manual"
    :abilities [{:req (req (and (ice? current-ice) (not (rezzed? current-ice))))
                 :async true
                 :effect (effect (expose eid current-ice))}]}

   "Grimoire"
   {:in-play [:memory 2]
    :events [{:event :runner-install
              :silent (req true)
              :req (req (has-subtype? target "Virus"))
              :effect (effect (add-counter target :virus 1))}]}

   "Heartbeat"
   {:in-play [:memory 1]
    :interactions {:prevent [{:type #{:net :brain :meat}
                              :req (req true)}]}
    :abilities [{:label "Prevent 1 damage"
                 :msg "prevent 1 damage"
                 :cost [:installed 1]
                 :effect (effect (damage-prevent :brain 1)
                                 (damage-prevent :meat 1)
                                 (damage-prevent :net 1))}]}

   "Hijacked Router"
   {:events [{:event :server-created
              :effect (effect (lose-credits :corp 1))
              :msg "force the Corp to lose 1 [Credits]"}
             {:event :successful-run
              :req (req (= target :archives))
              :optional
              {:prompt "Trash Hijacked Router to force the Corp to lose 3 [Credits]?"
               :yes-ability {:async true
                             :effect (req (system-msg state :runner "trashes Hijacked Router to force the Corp to lose 3 [Credits]")
                                          (wait-for (trash state :runner card {:unpreventable true})
                                                    (lose-credits state :corp 3)
                                                    (effect-completed state side eid)))}}}]}

   "Hippo"
   {:implementation "First encounter requirements not enforced"
    :abilities [{:label "Remove Hippo from the game: trash outermost piece of ICE if all subroutines were broken"
                 :req (req (and run
                                (pos? (count run-ices))
                                (rezzed? current-ice)
                                (empty? (remove :broken (:subroutines current-ice)))))
                 :async true
                 :effect (req (let [ice (last run-ices)]
                                (system-msg
                                  state :runner
                                  (str "removes Hippo from the game to trash " (card-str state ice)))
                                (move state :runner card :rfg)
                                (trash state :runner eid ice nil)))}]}

   "HQ Interface"
   {:in-play [:hq-access 1]}

   "Knobkierie"
   {:implementation "MU usage restriction not enforced"
    :in-play [:memory 3]
    :events [{:event :successful-run
              :req (req (and (first-event? state :runner :successful-run)
                             (pos? (count-virus-programs state))))
              :optional {:prompt "Place a virus counter?"
                         :autoresolve (get-autoresolve :auto-add)
                         :yes-ability {:prompt "Select an installed virus program for Knobkierie to add a virus counter to"
                                       :choices {:card #(and (installed? %)
                                                             (has-subtype? % "Virus")
                                                             (program? %))}
                                       :msg (msg "place 1 virus counter on " (:title target))
                                       :effect (effect (add-counter target :virus 1))}}}]
    :abilities [(set-autoresolve :auto-add "Knobkierie")]}

   "Lemuria Codecracker"
   {:abilities [{:cost [:click 1 :credit 1]
                 :req (req (some #{:hq} (:successful-run runner-reg)))
                 :choices {:card installed?}
                 :effect (effect (expose eid target))
                 :msg "expose 1 card"}]}

   "LLDS Memory Diamond"
   {:in-play [:link 1 :memory 1 :hand-size 1]}

   "LLDS Processor"
   {:events [{:event :runner-install
              :silent (req true)
              :req (req (has-subtype? target "Icebreaker"))
              :effect (effect (pump target 1 :end-of-turn))}]}

   "Lockpick"
   {:recurring 1
    :interactions {:pay-credits {:req (req (and (= :ability (:source-type eid))
                                                (has-subtype? target "Decoder")
                                                (has-subtype? target "Icebreaker")))
                                 :type :recurring}}}

   "Logos"
   {:in-play [:memory 1 :hand-size 1]
    :events [{:event :agenda-scored
              :player :runner :prompt "Choose a card" :msg (msg "add 1 card to their Grip from their Stack")
              :choices (req (cancellable (:deck runner)))
              :effect (effect (trigger-event :searched-stack nil)
                              (shuffle! :deck)
                              (move target :hand))}]}

   "Lucky Charm"
   {:interactions {:prevent [{:type #{:end-run}
                              :req (req (and (some #{:hq} (:successful-run runner-reg))
                                             (corp? (:card-cause target))))}]}
    :abilities [{:msg "prevent the run from ending"
                 :req (req (some #{:hq} (:successful-run runner-reg)))
                 :cost [:remove-from-game]
                 :effect (effect (end-run-prevent))}]}

   "Mâché"
   {:abilities [{:label "Draw 1 card"
                 :msg "draw 1 card"
                 :cost [:power 3]
                 :async true
                 :effect (effect (draw :runner eid 1 nil))}]
    :events [{:event :runner-trash
              :once :per-turn
              :req (req (and (some corp? targets)
                             (:access @state)
                             (:trash target)))
              :effect (effect (system-msg (str "places " (trash-cost state side target) " power counters on Mâché"))
                              (add-counter card :power (trash-cost state side target)))}]}

   "Masterwork (v37)"
   {:in-play [:memory 1]
    :events [{:event :run
              :optional
              {:async true
               :interactive (req true)
               :req (req (some #(and (hardware? %)
                                     (can-pay? state side eid card nil
                                               [:credit (install-cost state side % {:cost-bonus 1})]))
                               (:hand runner)))
               :prompt "Pay 1 [Credit] to install a hardware?"
               :yes-ability {:async true
                             :prompt "Select a piece of hardware"
                             :choices
                             {:req (req (and (in-hand? target)
                                             (hardware? target)
                                             (can-pay? state side eid card nil
                                                       [:credit (install-cost state side target {:cost-bonus 1})])))}
                             :msg (msg "install " (:title target) " from the grip, paying 1 [Credit] more")
                             :effect (effect (runner-install eid target {:cost-bonus 1}))}}}
             {:event :runner-install
              :async true
              :interactive (req true)
              :req (req (and (hardware? target)
                             (first-event? state side :runner-install #(hardware? (first %)))))
              :effect (effect (draw eid 1 nil))}]}

   "Māui"
   {:in-play [:memory 2]
    :recurring (effect (set-prop card :rec-counter (count (:ices (get-in @state [:corp :servers :hq])))))
    :effect (effect (set-prop card :rec-counter (count (:ices (get-in @state [:corp :servers :hq])))))
    :interactions {:pay-credits {:req (req (= :hq (get-in @state [:run :server 0])))
                                 :type :recurring}}}

   "Maw"
   (let [ability {:label "Trash a card from HQ"
                  :req (req (and (= 1 (get-in @state [:runner :register :no-trash-or-steal]))
                                 (pos? (count (:hand corp)))
                                 (not= (first (:zone target)) :discard)))
                  :once :per-turn
                  :msg "force the Corp to trash a random card from HQ"
                  :effect (req (let [card-to-trash (first (shuffle (:hand corp)))
                                     card-seen? (same-card? target card-to-trash)
                                     card-to-trash (if card-seen? (assoc card-to-trash :seen true)
                                                     card-to-trash)]
                                 ;; toggle access flag to prevent Hiro issue #2638
                                 (swap! state dissoc :access)
                                 (trash state :corp card-to-trash)
                                 (swap! state assoc :access true)))}]
     {:in-play [:memory 2]
      :abilities [ability]
      :events [(assoc ability :event :post-access-card)]})

   "Maya"
   {:in-play [:memory 2]
    :abilities [{:once :per-turn
                 :async true
                 :label "Move this accessed card to bottom of R&D"
                 :req (req (when-let [accessed-card (-> @state :runner :prompt first :card)]
                             (in-deck? accessed-card)))
                 :msg "move the card just accessed to the bottom of R&D"
                 :effect (req (let [accessed-card (-> @state :runner :prompt first :card)]
                                (move state :corp accessed-card :deck)
                                (wait-for (gain-tags state :runner (make-eid state) 1)
                                          (close-access-prompt state side))))}
                {:once :per-turn
                 :label "Move a previously accessed card to bottom of R&D"
                 :effect (effect (resolve-ability
                                   {:async true
                                    ;; only allow targeting cards that were accessed this turn
                                    :choices {:card #(some (fn [accessed-card]
                                                             (same-card? % accessed-card))
                                                           (map first (turn-events state side :access)))}
                                    :msg (msg "move " (:title target) " to the bottom of R&D")
                                    :effect (req (move state :corp target :deck)
                                                 (gain-tags state :runner eid 1)
                                                 (swap! state update-in [side :prompt] rest)
                                                 (when-let [run (:run @state)]
                                                   (when (and (:ended run)
                                                              (empty? (get-in @state [:runner :prompt])))
                                                     (handle-end-run state :runner))))}
                                   card nil))}]}

   "MemStrips"
   {:implementation "MU usage restriction not enforced"
    :in-play [:memory 3]}

   "Mind's Eye"
   {:in-play [:memory 1]
    :implementation "Power counters added automatically"
    :events [{:event :successful-run
              :silent (req true)
              :req (req (= target :rd))
              :effect (effect (add-counter card :power 1))}]
    :abilities [{:async true
                 :cost [:click 1 :power 3]
                 :msg "access the top card of R&D"
                 :effect (req (do-access state side eid [:rd] {:no-root true}))}]}

   "Mirror"
   {:in-play [:memory 2]
    :events [{:event :successful-run
              :async true
              :req (req (= target :rd))
              :effect (effect (continue-ability
                                {:prompt "Select a card and replace 1 spent [Recurring Credits] on it"
                                 :choices {:card #(< (get-counters % :recurring) (:recurring (card-def %) 0))}
                                 :msg (msg "replace 1 spent [Recurring Credits] on " (:title target))
                                 :effect (effect (add-prop target :rec-counter 1))}
                                card nil))}]}

   "Monolith"
   (let [mhelper
         (fn mh [n]
           {:prompt "Select a program to install"
            :choices {:req (req (and (program? target)
                                     (in-hand? target)
                                     (can-pay? state side eid card nil
                                               [:credit (install-cost state side target {:cost-bonus -4})])))}
            :async true
            :effect (req (wait-for (runner-install state side target {:cost-bonus -4})
                                   (continue-ability state side (when (< n 3) (mh (inc n))) card nil)))})]
     {:interactions {:prevent [{:type #{:net :brain}
                                :req (req true)}]}
      :in-play [:memory 3]
      :async true
      :effect (effect (continue-ability (mhelper state side eid card 1) card nil))
      :abilities [{:msg (msg "prevent 1 brain or net damage")
                   :cost [:trash-program-from-grip 1]
                   :effect (effect (damage-prevent :brain 1)
                                   (damage-prevent :net 1))}]})

   "Muresh Bodysuit"
   {:events [{:event :pre-damage
              :once :per-turn :once-key :muresh-bodysuit
              :req (req (= target :meat))
              :msg "prevent the first meat damage this turn"
              :effect (effect (damage-prevent :meat 1))}]}

   "Net-Ready Eyes"
   {:effect (effect (damage eid :meat 2 {:unboostable true :card card}))
    :msg "suffer 2 meat damage"
    :events [{:event :run
              :req (req (some #(and (program? %)
                                    (has-subtype? % "Icebreaker"))
                              (all-active-installed state :runner)))
              :choices {:card #(and (installed? %)
                                    (has-subtype? % "Icebreaker"))}
              :msg (msg "give " (:title target) " +1 strength")
              :effect (effect (pump target 1 :end-of-run))}]}

   "NetChip"
   {:abilities [{:async true
                 :label "Install a program on NetChip"
                 :req (req (empty? (:hosted card)))
                 :effect (effect
                           (continue-ability
                             (let [n (count (filter #(= (:title %) (:title card)) (all-active-installed state :runner)))]
                               {:async true
                                :cost [:click 1]
                                :prompt "Select a program in your Grip to install on NetChip"
                                :choices {:card #(and (program? %)
                                                      (runner-can-install? state side % false)
                                                      (<= (:memoryunits %) n)
                                                      (in-hand? %))}
                                :msg (msg "host " (:title target))
                                :effect (effect (update! (assoc (get-card state card)
                                                                :hosted-programs
                                                                (cons (:cid target) (:hosted-programs card))))
                                                (runner-install eid target {:host-card card :no-mu true}))})
                             card nil))}
                {:async true
                 :label "Host an installed program on NetChip"
                 :req (req (empty? (:hosted card)))
                 :effect (effect
                           (continue-ability
                             (let [n (count (filter #(= (:title %) (:title card)) (all-active-installed state :runner)))]
                               {:prompt "Select an installed program to host on NetChip"
                                :choices {:card #(and (program? %)
                                                      (<= (:memoryunits %) n)
                                                      (installed? %))}
                                :msg (msg "host " (:title target))
                                :effect (effect (host card target)
                                                (free-mu (:memoryunits target))
                                                (update! (assoc (get-card state card)
                                                                :hosted-programs
                                                                (cons (:cid target) (:hosted-programs card)))))})
                             card nil))}]
    :events [{:event :card-moved
              :req (req (some #{(:cid target)} (:hosted-programs card)))
              :effect (effect (update! (assoc card
                                              :hosted-programs
                                              (remove #(= (:cid target) %) (:hosted-programs card))))
                              (use-mu (:memoryunits target)))}]}

   "Obelus"
   {:in-play [:memory 1]
    :effect (req (change-hand-size state :runner (count-tags state)))
    :leave-play (req (change-hand-size state :runner (- (count-tags state))))
    :events [{:event :successful-run-ends
              :once :per-turn
              :req (req (and (#{:rd :hq} (first (:server target)))
                             (first-event? state side :successful-run-ends
                                           #(#{:rd :hq} (first (:server (first %)))))))
              :msg (msg "draw " (total-cards-accessed target) " cards")
              :async true
              :effect (effect (draw eid (total-cards-accessed target) nil))}
             ;; Events for tracking hand size
             {:event :runner-gain-tag
              :effect (req (change-hand-size state :runner target))}
             {:event :runner-lose-tag
              :effect (req (change-hand-size state :runner (- target)))}
             {:event :runner-additional-tag-change
              :effect (req (change-hand-size state :runner target))}]}

   "Omni-drive"
   {:recurring 1
    :abilities [{:async true
                 :label "Install and host a program of 1[Memory Unit] or less on Omni-drive"
                 :req (req (empty? (:hosted card)))
                 :cost [:click 1]
                 :prompt "Select a program of 1[Memory Unit] or less to install on Omni-drive from your grip"
                 :choices {:card #(and (program? %)
                                       (<= (:memoryunits %) 1)
                                       (in-hand? %))}
                 :msg (msg "host " (:title target))
                 :effect (effect (update! (assoc (get-card state card) :Omnidrive-prog (:cid target)))
                                 (runner-install eid target {:host-card card :no-mu true}))}
                {:label "Host an installed program of 1[Memory Unit] or less on Omni-drive"
                 :prompt "Select an installed program of 1[Memory Unit] or less to host on Omni-drive"
                 :choices {:card #(and (program? %)
                                       (<= (:memoryunits %) 1)
                                       (installed? %))}
                 :msg (msg "host " (:title target))
                 :effect (effect (host card target)
                                 (free-mu (:memoryunits target))
                                 (update! (assoc (get-card state card) :Omnidrive-prog (:cid target))))}]
    :events [{:event :card-moved
              :req (req (= (:cid target) (:Omnidrive-prog (get-card state card))))
              :effect (effect (update! (dissoc card :Omnidrive-prog))
                              (use-mu (:memoryunits target)))}]
    :interactions {:pay-credits {:req (req (and (= :ability (:source-type eid))
                                                (program? target)
                                                (same-card? card (:host target))))
                                 :type :recurring}}}

   "Paragon"
   {:in-play [:memory 1]
    :events [{:event :successful-run
              :req (req (first-event? state side :successful-run))
              :async true
              :interactive (get-autoresolve :autofire (complement never?))
              :silent (get-autoresolve :autofire never?)
              :effect (effect
                        (show-wait-prompt :corp "Runner to decide if they will use Paragon")
                        (continue-ability
                          {:optional
                           {:player :runner
                            :autoresolve (get-autoresolve :auto-fire)
                            :prompt "Use Paragon?"
                            :yes-ability
                            {:msg "gain 1 [Credit] and look at the top card of Stack"
                             :async true
                             :effect (effect
                                       (gain-credits :runner 1)
                                       (continue-ability
                                         {:player :runner
                                          :optional
                                          {:prompt (msg "Add " (:title (first (:deck runner))) " to bottom of Stack?")
                                           :yes-ability
                                           {:msg "add the top card of Stack to the bottom"
                                            :effect (effect (move :runner (first (:deck runner)) :deck)
                                                            (clear-wait-prompt :corp))}
                                           :no-ability {:effect (effect (clear-wait-prompt :corp)
                                                                        (system-msg "does not add the top card of the Stack to the bottom"))}}}
                                         card nil))}
                            :no-ability {:effect (effect (clear-wait-prompt :corp)
                                                         (system-msg "does not gain 1 [Credit] and look at the top card of the Stack"))}}}
                          card nil))}]
    :abilities [(set-autoresolve :auto-fire "Paragon")]}

   "Patchwork"
   (let [patchwork-ability {:once :per-turn
                            :effect (effect (update! (assoc-in card [:special :patchwork] true)))}]
    {:in-play [:memory 1]
     :interactions {:pay-credits {:req (req (and (or (= :play (:source-type eid))
                                                     (= :runner-install (:source-type eid)))
                                                 ;; We need at least one card (that is not the card played) in hand
                                                 (not-empty (remove (partial same-card? target) (:hand runner)))
                                                 ;; Patchwork wasn't used in the traditional way
                                                 (not (get-in card [:special :patchwork]))
                                                 ;; Check if Patchwork can trigger
                                                 (can-trigger? state side patchwork-ability card targets)))
                                  :custom-amount 2
                                  :custom (req (let [cost-type (str (when (= :play (:source-type eid)) "play")
                                                                    (when (= :runner-install (:source-type eid)) "install"))
                                                     patchwork card
                                                     targetcard target]
                                                 (continue-ability
                                                   state side
                                                   {:prompt (str "Trash a card to lower the " cost-type
                                                                 " cost of " (:title targetcard) " by 2 [Credits].")
                                                    :priority 2
                                                    :async true
                                                    :choices {:card #(and (in-hand? %)
                                                                          (runner? %)
                                                                          (not (same-card? % targetcard)))}
                                                    :msg (msg "trash " (:title target) " to lower the " cost-type " cost of "
                                                              (:title targetcard) " by 2 [Credits]")
                                                    :effect (req (trash state side target {:unpreventable true})
                                                                 (register-once state patchwork-ability patchwork)
                                                                 (effect-completed state side (make-result eid 2))) ; provide 2 credits
                                                    :cancel-effect (effect (effect-completed (make-result eid 0)))} ; provide 0 credits
                                                   nil nil)))
                                  :type :custom}}})

   "Plascrete Carapace"
   {:data [:counter {:power 4}]
    :interactions {:prevent [{:type #{:meat}
                              :req (req true)}]}
    :abilities [{:cost [:power 1]
                 :msg "prevent 1 meat damage"
                 :effect (req (damage-prevent state side :meat 1)
                              (when (zero? (get-counters (get-card state card) :power))
                                (trash state side card {:unpreventable true})))}]}

   "Polyhistor"
   (let [abi {:optional
              {:prompt "Draw 1 card to force the Corp to draw 1 card?"
               :yes-ability {:msg "draw 1 card and force the Corp to draw 1 card"
                             :async true
                             :effect (req (wait-for (draw state :runner 1 nil)
                                                    (draw state :corp eid 1 nil)))}
               :no-ability {:effect (req (system-msg state side (str "does not use Polyhistor"))
                                         (effect-completed state side eid))}}}]
     {:in-play [:link 1 :memory 1]
      :events [{:event :pass-ice
                :req (req (and (= (:server run) [:hq])
                               (= (:position run) 1) ; trigger when last ICE passed
                               (pos? (count (:deck runner)))))
                :async true
                :once :per-turn
                :effect (req (continue-ability state :runner abi card nil))}
               {:event :run
                :req (req (and (= (:server run) [:hq])
                               (zero? (:position run)) ; trigger on unprotected HQ
                               (pos? (count (:deck runner)))))
                :async true
                :once :per-turn
                :effect (req (continue-ability state :runner abi card nil))}]})

   "Prepaid VoicePAD"
   {:recurring 1
    :interactions {:pay-credits {:req (req (= :play (:source-type eid)))
                                 :type :recurring}}}

   "Public Terminal"
   {:recurring 1
    :interactions {:pay-credits {:req (req (and (= :play (:source-type eid))
                                                (has-subtype? target "Run")))
                                 :type :recurring}}}

   "Q-Coherence Chip"
   {:in-play [:memory 1]
    :events (let [e {:async true
                     :interactive (req true)
                     :req (req (some program? targets))
                     :effect (effect (system-msg (str "trashes Q-Coherence Chip"))
                                     (trash eid card nil))}]
              [(assoc e :event :runner-trash)
               (assoc e :event :corp-trash)])}

   "Qianju PT"
   {:flags {:runner-phase-12 (req true)}
    :abilities [{:label "Lose [Click], avoid 1 tag (start of turn)"
                 :once :per-turn
                 :req (req (:runner-phase-12 @state))
                 :effect (effect (update! (assoc card :qianju-active true)))
                 :msg "lose [Click] and avoid the first tag received until their next turn"}]
    :events [{:event :corp-turn-ends
              :effect (effect (update! (dissoc card :qianju-active)))}
             {:event :runner-turn-begins
              :req (req (:qianju-active card))
              :effect (effect (lose :click 1))}
             {:event :pre-tag
              :req (req (:qianju-active card))
              :msg "avoid the first tag received"
              :effect (effect (tag-prevent :runner 1)
                              (update! (dissoc card :qianju-active)))}]}

   "R&D Interface"
   {:in-play [:rd-access 1]}

   "Rabbit Hole"
   {:in-play [:link 1]
    :optional {:req (req (some #(when (= (:title %) "Rabbit Hole") %) (:deck runner)))
               :prompt "Install another Rabbit Hole?"
               :msg "install another Rabbit Hole"
               :yes-ability {:async true
                             :effect (req (trigger-event state side :searched-stack nil)
                                          (shuffle! state :runner :deck)
                                          (when-let [c (some #(when (= (:title %) "Rabbit Hole") %)
                                                             (:deck runner))]
                                            (runner-install state side eid c nil)))}}}

   "Ramujan-reliant 550 BMI"
   {:interactions {:prevent [{:type #{:net :brain}
                              :req (req true)}]}
    :abilities [{:async true
                 :req (req (not-empty (:deck runner)))
                 :effect (req (let [n (count (filter #(= (:title %) (:title card)) (all-active-installed state :runner)))]
                                (continue-ability
                                  state side
                                  {:prompt "Choose how much damage to prevent"
                                   :priority 50
                                   :choices {:number (req (min n (count (:deck runner))))}
                                   :msg (msg "trash " (join ", " (map :title (take target (:deck runner))))
                                             " from their Stack and prevent " target " damage")
                                   :cost [:trash]
                                   :effect (effect (damage-prevent :net target)
                                                   (damage-prevent :brain target)
                                                   (mill :runner target))}
                                  card nil)))}]}

   "Recon Drone"
   ; eventmap uses reverse so we get the most recent event of each kind into map
   (letfn [(eventmap [s]
             (into {} (reverse (get s :turn-events))))]
     {:interactions {:prevent [{:type #{:net :brain :meat}
                                :req (req (:access @state))}]}
      :abilities [{:req (req (= (:cid (second (:pre-damage (eventmap @state))))
                                (:cid (first (:pre-access-card (eventmap @state))))))
                   :effect (effect (resolve-ability
                                     {:prompt "Choose how much damage to prevent"
                                      :priority 50
                                      :choices {:number (req (min (last (:pre-damage (eventmap @state)))
                                                                  (:credit runner)))}
                                      :msg (msg "prevent " target " damage")
                                      :cost [:trash]
                                      :effect (effect (damage-prevent (first (:pre-damage (eventmap @state))) target)
                                                      (lose-credits target))}
                                     card nil))}]})

   "Record Reconstructor"
   {:events
    [{:event :successful-run
      :req (req (= target :archives))
      :effect (effect (add-run-effect
                        {:card card
                         :replace-access
                         {:prompt "Choose one faceup card to add to the top of R&D"
                          :req (req (seq (filter :seen (:discard corp))))
                          :choices (req (filter :seen (:discard corp)))
                          :msg (msg "add " (:title target) " to the top of R&D")
                          :effect (effect (move :corp target :deck {:front true}))}}))}]}

   "Reflection"
   {:in-play [:memory 1 :link 1]
    :events [{:event :jack-out
              :effect (req (let [card (first (shuffle (:hand corp)))]
                             (reveal state :corp card)
                             (system-msg state :runner (str  "force the Corp to reveal " (:title card) " from HQ"))))}]}

   "Replicator"
   (letfn [(hardware-and-in-deck? [target runner]
             (and (hardware? target)
                  (some #(= (:title %) (:title target)) (:deck runner))))]
     {:events [{:event :runner-install
                :interactive (req (hardware-and-in-deck? target runner))
                :silent (req (not (hardware-and-in-deck? target runner)))
                :optional {:prompt "Use Replicator to add a copy?"
                           :req (req (hardware-and-in-deck? target runner))
                           :yes-ability {:msg (msg "add a copy of " (:title target) " to their Grip")
                                         :effect (effect (trigger-event :searched-stack nil)
                                                   (shuffle! :deck)
                                                   (move (some #(when (= (:title %) (:title target)) %)
                                                               (:deck runner)) :hand))}}}]})

   "Respirocytes"
   (let [ability {:once :per-turn
                  :msg "draw 1 card and add a power counter to itself"
                  :async true
                  :effect (req (wait-for (draw state :runner 1 nil)
                                         (add-counter state side (get-card state card) :power 1)
                                         (if (= 3 (get-counters (get-card state card) :power))
                                           (do (system-msg state :runner "trashes Respirocytes as it reached 3 power counters")
                                               (trash state side eid card {:unpreventable true}))
                                           (effect-completed state side eid))))}]
     {:async true
      :effect (effect (damage eid :meat 1 {:unboostable true :card card}))
      :msg "suffer 1 meat damage"
      :events [{:event :runner-hand-change
                :req (req (and (zero? target)
                               (first-event? state side :runner-hand-change #(zero? (first %)))))
                :async true
                :effect (req (continue-ability state side ability card nil))}
               {:event :runner-turn-begins
                :req (req (empty? (:hand runner)))
                :async true
                :effect (effect (continue-ability ability card nil))}
               {:event :corp-turn-begins
                :req (req (empty? (:hand runner)))
                :async true
                :effect (effect (continue-ability ability card nil))}]})

   "Rubicon Switch"
   {:abilities [{:cost [:click 1]
                 :once :per-turn
                 :async true
                 :prompt "How many [Credits]?"
                 :choices :credit
                 :effect (effect (system-msg (str "spends a [Click] and " target " [Credit] on Rubicon Switch"))
                                 (continue-ability
                                   {:choices {:card #(and (ice? %)
                                                          (= :this-turn (:rezzed %))
                                                          (<= (:cost %) target))}
                                    :effect (effect (derez target))
                                    :msg (msg "derez " (:title target))}
                                   card nil))}]}

   "Security Chip"
   {:abilities [{:label "Add [Link] strength to a non-Cloud icebreaker until the end of the run"
                 :msg (msg "add " (:link runner) " strength to " (:title target) " until the end of the run")
                 :req (req (:run @state))
                 :prompt "Select one non-Cloud icebreaker"
                 :choices {:card #(and (has-subtype? % "Icebreaker")
                                       (not (has-subtype? % "Cloud"))
                                       (installed? %))}
                 :cost [:trash]
                 :effect (effect (pump target (:link runner) :end-of-run))}
                {:label "Add [Link] strength to any Cloud icebreakers until the end of the run"
                 :msg (msg "add " (:link runner) " strength to " (count targets) " Cloud icebreakers until the end of the run")
                 :req (req (:run @state))
                 :prompt "Select any number of Cloud icebreakers"
                 :choices {:max 50
                           :card #(and (has-subtype? % "Icebreaker")
                                       (has-subtype? % "Cloud")
                                       (installed? %))}
                 :cost [:trash]
                 :effect (req (doseq [t targets]
                                (pump state side t (:link runner) :end-of-run)
                                (update-breaker-strength state side t)))}]}

   "Security Nexus"
   {:implementation "Bypass is manual"
    :in-play [:memory 1 :link 1]
    :abilities [{:req (req (:run @state))
                 :once :per-turn
                 :async true
                 :msg "force the Corp to initiate a trace"
                 :label "Trace 5 - Give the Runner 1 tag and end the run"
                 :trace {:base 5
                         :successful {:msg "give the Runner 1 tag and end the run"
                                      :effect (effect (gain-tags :runner eid 1)
                                                      (end-run))}
                         :unsuccessful {:msg "bypass the current ICE"}}}]}

   "Severnius Stim Implant"
   (letfn [(implant-fn [srv kw]
             {:prompt "Choose at least 2 cards in your Grip to trash with Severnius Stim Implant"
              :choices {:max (req (count (:hand runner)))
                        :card #(and (runner? %)
                                    (in-hand? %))}
              :msg (msg "trash " (quantify (count targets) "card")
                        " and access " (quantify (quot (count targets) 2) "additional card"))
              :effect (req (let [bonus (quot (count targets) 2)]
                             (wait-for (trash-cards state side targets {:unpreventable true
                                                                        :suppress-event true})
                                       (make-run state side srv nil card)
                                       (register-events
                                         state side card
                                         [{:event :pre-access
                                           :silent (req true)
                                           :effect (effect (access-bonus kw bonus))}
                                          {:event :run-ends
                                           :effect (effect (unregister-events card))}])
                                       (effect-completed state side eid))))})]
     {:abilities [{:req (req (<= 2 (count (:hand runner))))
                   :cost [:click 1]
                   :prompt "Choose a server to run with Severnius Stim Implant"
                   :choices ["HQ" "R&D"]
                   :effect (effect (continue-ability (implant-fn target (if (= target "HQ") :hq :rd)) card nil))}]
      :events [{:event :pre-access}
               {:event :run-ends}]})

   "Şifr"
   {:in-play [:memory 2]
    :abilities [{:once :per-turn
                 :req (req (rezzed? current-ice))
                 :msg (msg "lower their maximum hand size by 1 and lower the strength of " (:title current-ice) " to 0")
                 :effect (effect (lose :runner :hand-size 1)
                                 (update! (assoc card :sifr-target current-ice :sifr-used true))
                                 (update-ice-strength current-ice))}]
    :constant-effects [{:type :ice-strength
                        :req (req (same-card? target (:sifr-target card)))
                        :value (req (- (get-strength target)))}]
    :events [{:event :runner-turn-begins
              :req (req (:sifr-used card))
              :effect (effect (gain :runner :hand-size 1)
                              (update! (dissoc card :sifr-used)))}
             {:event :run-ends
              :effect (effect (update! (dissoc card :sifr-target)))}]}

   "Silencer"
   {:recurring 1
    :interactions {:pay-credits {:req (req (and (= :ability (:source-type eid))
                                                (has-subtype? target "Killer")
                                                (has-subtype? target "Icebreaker")))
                                 :type :recurring}}}

   "Skulljack"
   {:effect (effect (damage eid :brain 1 {:card card}))
    :constant-effects [{:type :trash-cost
                        :value -1}]}

   "Spinal Modem"
   {:in-play [:memory 1]
    :recurring 2
    :events [{:event :successful-trace
              :req (req run)
              :effect (effect (system-msg (str "suffers 1 brain damage from Spinal Modem"))
                              (damage eid :brain 1 {:card card}))}]
    :interactions {:pay-credits {:req (req (and (= :ability (:source-type eid))
                                                (has-subtype? target "Icebreaker")))
                                 :type :recurring}}}

   "Sports Hopper"
   {:in-play [:link 1]
    :abilities [{:label "Draw 3 cards"
                 :msg "draw 3 cards"
                 :async true
                 :cost [:trash]
                 :effect (effect (draw :runner eid 3 nil))}]}

   "Spy Camera"
   {:abilities [{:cost [:click 1]
                 :async true
                 :label "Look at the top X cards of your Stack"
                 :msg "look at the top X cards of their Stack and rearrange them"
                 :effect (req (show-wait-prompt state :corp "Runner to rearrange the top cards of their stack")
                              (let [n (count (filter #(= (:title %) (:title card))
                                                     (all-active-installed state :runner)))
                                    from (take n (:deck runner))]
                                (if (pos? (count from))
                                  (continue-ability state side (reorder-choice :runner :corp from '()
                                                                               (count from) from) card nil)
                                  (do (clear-wait-prompt state :corp)
                                      (effect-completed state side eid)))))}
                {:label "Look at the top card of R&D"
                 :msg "trash it and look at the top card of R&D"
                 :cost [:trash]
                 :effect (effect (prompt! card (str "The top card of R&D is " (:title (first (:deck corp)))) ["OK"] {}))}]}

   "Supercorridor"
   {:in-play [:memory 2 :hand-size 1]
    :events [{:event :runner-turn-ends
              :req (req (= (:credit runner) (:credit corp)))
              :interactive (get-autoresolve :autofire (complement never?))
              :silent (get-autoresolve :autofire never?)
              :async true
              :effect (req (show-wait-prompt
                             state :corp
                             "Runner to decide if they will gain credits from Supercorridor")
                           (continue-ability
                             state :runner
                             {:optional
                              {:prompt "Gain credits from Supercorridor?"
                               :player :runner
                               :autoresolve (get-autoresolve :auto-fire)
                               :yes-ability {:msg "gain 2 [Credits]"
                                             :effect (req (gain-credits state :runner 2)
                                                          (clear-wait-prompt state :corp))}
                               :no-ability {:effect (req (system-msg
                                                           state :runner
                                                           "chooses not to gain 2 [Credits] from Supercorridor")
                                                         (clear-wait-prompt state :corp))}}}
                             card nil))}]
    :abilities [(set-autoresolve :auto-fire "Supercorridor")]}

   "Swift"
   {:in-play [:memory 1]
    :events [{:event :play-event
              :req (req (and (has-subtype? target "Run")
                             (first-event? state side :play-event #(has-subtype? (first %) "Run"))))
              :msg "gain a [click]"
              :effect (effect (gain :click 1))}]}

   "The Gauntlet"
   {:implementation "Requires Runner to manually (and honestly) set how many ICE were broken directly protecting HQ"
    :in-play [:memory 2]
    :events [{:event :post-successful-run
              :req (req (and (= :hq target)
                             run))
              :silent (req true)
              :async true
              :effect (effect (continue-ability
                                {:prompt "How many ICE protecting HQ did you break all subroutines on?"
                                 ;; Makes number of ice on server (HQ) the upper limit.
                                 ;; This should work since trashed ice do not count according to UFAQ
                                 :choices {:number (req (count (get-in @state [:corp :servers :hq :ices])))}
                                 :effect (effect (access-bonus :hq target))}
                                card nil))}]}

   "The Personal Touch"
   {:hosting {:card #(and (has-subtype? % "Icebreaker")
                          (installed? %))}
    :effect (effect (update-breaker-strength (:host card)))
    :constant-effects [{:type :breaker-strength
                        :req (req (same-card? target (:host card)))
                        :value 1}]}

   "The Toolbox"
   {:in-play [:link 2 :memory 2]
    :recurring 2
    :interactions {:pay-credits {:req (req (and (= :ability (:source-type eid))
                                                (has-subtype? target "Icebreaker")))
                                 :type :recurring}}}

   "Titanium Ribs"
   {:async true
    :effect (effect (enable-runner-damage-choice)
                    (system-msg (str "suffers 2 meat damage from installing Titanium Ribs"))
                    (damage eid :meat 2 {:unboostable true :card card}))
    :leave-play (req (swap! state update-in [:damage] dissoc :damage-choose-runner))
    :events [{:event :pre-resolve-damage
              :async true
              :req (req (and (pos? (last targets))
                             (runner-can-choose-damage? state)
                             (not (get-in @state [:damage :damage-replace]))))
              :effect (req (let [dtype target
                                 src (second targets)
                                 dmg (last targets)
                                 hand (:hand runner)]
                             (show-wait-prompt state :corp "Runner to use Titanium Ribs to choose cards to be trashed")
                             (continue-ability
                               state :runner
                               (if (< (count hand) dmg)
                                 {:async true
                                  :effect (req (clear-wait-prompt state :corp)
                                               (chosen-damage state :runner hand)
                                               (effect-completed state side eid))}
                                 {:prompt (msg "Select " dmg " cards to trash for the " (name dtype) " damage")
                                  :choices {:max dmg
                                            :all true
                                            :card #(and (in-hand? %)
                                                        (runner? %))}
                                  :msg (msg "trash " (join ", " (map :title targets)))
                                  :effect (req (clear-wait-prompt state :corp)
                                               (chosen-damage state :runner targets)
                                               (effect-completed state side eid))})
                               card nil)))}]}

   "Top Hat"
   {:events [{:event :successful-run
              :interactive (req true)
              :req (req (and (= target :rd)
                             (not= (:max-access run) 0)))
              :async true
              :effect
              (effect
                (continue-ability
                  {:optional
                   {:prompt "Use Top Hat to access a single card?"
                    :yes-ability
                    {:req (req (pos? (count (:deck corp))))
                     :prompt "Which card from the top of R&D would you like to access? (Card 1 is on top.)"
                     :choices (map str (take (count (:deck corp)) (range 1 6)))
                     :msg (msg "only access the card at position " target " of R&D")
                     :effect (effect
                               (add-run-effect
                                 (let [t target]
                                   {:card card
                                    :replace-access
                                    {:mandatory true
                                     :async true
                                     :effect (req (if (empty? (get-cards-to-access state))
                                                    (access-card state side eid (nth (:deck corp) (dec (str->int t))) "an unseen card")
                                                    (effect-completed state side eid)))}})))}}}
                  card nil))}]}

   "Turntable"
   {:in-play [:memory 1]
    :events [{:event :agenda-stolen
              :interactive (req true)
              :req (req (not (empty? (:scored corp))))
              :async true
              :effect (req
                        (let [stolen target]
                          (continue-ability
                            state side
                            {:optional
                             {:prompt (msg "Swap " (:title stolen) " for an agenda in the Corp's score area?")
                              :yes-ability
                              {:async true
                               :effect (req
                                         (continue-ability
                                           state side
                                           {:prompt (str "Select a scored Corp agenda to swap with " (:title stolen))
                                            :choices {:card #(in-corp-scored? state side %)}
                                            :effect (req (let [scored target]
                                                           (swap-agendas state side scored stolen)
                                                           (system-msg state side (str "uses Turntable to swap "
                                                                                       (:title stolen) " for " (:title scored)))
                                                           (effect-completed state side eid)))}
                                           card targets))}}}
                            card targets)))}]}

   "Ubax"
   (let [ability {:req (req (:runner-phase-12 @state))
                  :msg "draw 1 card"
                  :label "Draw 1 card (start of turn)"
                  :once :per-turn
                  :async true
                  :effect (effect (draw eid 1 nil))}]
     {:in-play [:memory 1]
      :flags {:runner-turn-draw true
              :runner-phase-12 (req (< 1 (count (filter #(card-flag? % :runner-turn-draw true)
                                                        (cons (get-in @state [:runner :identity])
                                                              (all-active-installed state :runner))))))}
      :events [(assoc ability :event :runner-turn-begins)]
      :abilities [ability]})

   "Unregistered S&W '35"
   {:abilities
    [{:cost [:click 2]
      :req (req (some #{:hq} (:successful-run runner-reg)))
      :label "trash a Bioroid, Clone, Executive or Sysop"
      :prompt "Select a Bioroid, Clone, Executive, or Sysop to trash"
      :choices {:card #(and (rezzed? %)
                            (or (has-subtype? % "Bioroid")
                                (has-subtype? % "Clone")
                                (has-subtype? % "Executive")
                                (has-subtype? % "Sysop"))
                            (or (and (= (last (:zone %)) :content) (is-remote? (second (:zone %))))
                                (= (last (:zone %)) :onhost)))}
      :msg (msg "trash " (:title target)) :effect (effect (trash target))}]}

   "Vigil"
   (let [ability {:req (req (and (:runner-phase-12 @state) (= (count (:hand corp)) (hand-size state :corp))))
                  :msg "draw 1 card"
                  :label "Draw 1 card (start of turn)"
                  :once :per-turn
                  :async true
                  :effect (effect (draw eid 1 nil))}]
     {:in-play [:memory 1]
      :events [(assoc ability :event :runner-turn-begins)]
      :abilities [ability]})

   "Window"
   {:abilities [{:cost [:click 1] :msg "draw 1 card from the bottom of their Stack"
                 :effect (effect (move (last (:deck runner)) :hand))}]}

   "Zamba"
   {:implementation "Credit gain is automatic"
    :in-play [:memory 2]
    :events [{:event :expose
              :effect (effect (gain-credits :runner 1))
              :msg "gain 1 [Credits]"}]}

   "Zer0"
   {:abilities [{:cost [:click 1 :net 1]
                 :once :per-turn
                 :msg "gain 1 [Credits] and draw 2 cards"
                 :async true
                 :effect (effect (gain-credits 1)
                                 (draw eid 2 nil))}]}})
